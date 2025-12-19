package com.meshcentral.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.X509v3CertificateBuilder
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.lang.Exception
import java.math.BigInteger
import java.util.Random
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Security
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import kotlin.math.absoluteValue

private const val NOTIFICATION_ID = 2001
private const val SERVICE_CHANNEL_ID = "MeshAgentServiceChannel"

class MeshAgentService : Service(), MeshAgentHost {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionRetryTimer: CountDownTimer? = null

    companion object {
        init {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    override val context: Context
        get() = this

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.meshcentral_service_notification)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshPreferences()
        ensureServerLink()

        if (g_autoConnect && !g_userDisconnect) {
            startMeshAgentConnection()
            startRetryTimer()
        } else {
            stopMeshAgentConnection()
            stopRetryTimer()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopRetryTimer()
        stopMeshAgentConnection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                getString(R.string.meshcentral_service_notification),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val builder = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(content)
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return builder.build()
    }

    private fun refreshPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        g_autoStart = prefs.getBoolean("pref_autostart", false)
        g_autoConnect = prefs.getBoolean("pref_autoconnect", false)
        g_autoConsent = prefs.getBoolean("pref_autoconsent", false)
    }

    private fun ensureServerLink() {
        if (serverLink != null) return
        val sharedPreferences = getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        serverLink = sharedPreferences?.getString("qrmsh", null)
    }

    private fun startMeshAgentConnection() {
        if (meshAgent != null) return
        if (serverLink == null) { return }
        if (g_userDisconnect) return
        g_userDisconnect = false

        val host = getServerHostFromLink(serverLink)
        val hash = getServerHashFromLink(serverLink)
        val group = getDevGroupFromLink(serverLink)
        if ((host == null) || (hash == null) || (group == null)) return

        ensureAgentCertificate()

        meshAgent = MeshAgent(this, host, hash, group)
        meshAgent?.Start()
    }

    private fun stopMeshAgentConnection() {
        if (meshAgent != null) {
            meshAgent?.Stop()
            meshAgent = null
        }
    }

    private fun ensureAgentCertificate() {
        if ((agentCertificate != null) && (agentCertificateKey != null)) return

        val sharedPreferences = getSharedPreferences("meshagent", Context.MODE_PRIVATE)
        val certb64 = sharedPreferences?.getString("agentCert", null)
        val keyb64 = sharedPreferences?.getString("agentKey", null)

        if ((certb64 == null) || (keyb64 == null)) {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048, SecureRandom())
            val keypair = keyGen.generateKeyPair()

            var serial = BigInteger("12345678")
            try { serial = BigInteger.valueOf(Random().nextInt().toLong().absoluteValue) } catch (ex: Exception) { }

            val builder = JcaX509v3CertificateBuilder(
                X500Name("CN=android.agent.meshcentral.com"),
                serial,
                Date(System.currentTimeMillis() - 86400000L * 365),
                Date(253402300799000L),
                X500Name("CN=android.agent.meshcentral.com"),
                keypair.public
            )
            agentCertificate = JcaX509CertificateConverter().setProvider("SC").getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keypair.private))
            )
            agentCertificateKey = keypair.private

            sharedPreferences?.edit()?.putString("agentCert", Base64.encodeToString(agentCertificate?.encoded, Base64.DEFAULT))?.apply()
            sharedPreferences?.edit()?.putString("agentKey", Base64.encodeToString(agentCertificateKey?.encoded, Base64.DEFAULT))?.apply()
        } else {
            agentCertificate = CertificateFactory.getInstance("X509").generateCertificate(
                ByteArrayInputStream(Base64.decode(certb64, Base64.DEFAULT))
            ) as X509Certificate
            val keySpec = PKCS8EncodedKeySpec(Base64.decode(keyb64, Base64.DEFAULT))
            agentCertificateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
        }
    }

    private fun startRetryTimer() {
        if (connectionRetryTimer == null) {
            connectionRetryTimer = object : CountDownTimer(120000000, 10000) {
                override fun onTick(millisUntilFinished: Long) {
                    if ((meshAgent == null) && (!g_userDisconnect) && g_autoConnect) {
                        startMeshAgentConnection()
                    }
                }

                override fun onFinish() {
                    stopRetryTimer()
                    startRetryTimer()
                }
            }
        }
        connectionRetryTimer?.start()
    }

    private fun stopRetryTimer() {
        connectionRetryTimer?.cancel()
        connectionRetryTimer = null
    }

    override fun agentStateChanged() {
        Log.d("MeshAgentService", "Agent state changed: ${meshAgent?.state}")
    }

    override fun refreshInfo() {
        // No UI to update.
    }

    override fun returnToMainScreen() {
        launchActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
    }

    override fun openUrl(url: String): Boolean {
        launchActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_URL, url)
        })
        return true
    }

    override fun showAlertMessage(title: String, msg: String) {
        launchActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_ALERT_TITLE, title)
            putExtra(MainActivity.EXTRA_ALERT_MESSAGE, msg)
        })
    }

    override fun showToastMessage(msg: String) {
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun startProjection() {
        launchActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_REQUEST_PROJECTION, true)
        })
    }

    override fun stopProjection() {
        startService(ScreenCaptureService.getStopIntent(this))
    }

    override fun runOnUiThread(action: () -> Unit) {
        mainHandler.post(action)
    }

    override fun launchActivity(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun launchIntentSenderForResult(intentSender: IntentSender, pad: PendingActivityData) {
        Log.w("MeshAgentService", "File operation requires user interaction; skipping")
        pad.tunnel.deleteFileEx(pad)
    }
}
