package com.meshcentral.agent

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import kotlin.math.absoluteValue
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.X509v3CertificateBuilder
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder

object AgentCertificateManager {
    private const val PREF_AGENT_CERT = "agentCert"
    private const val PREF_AGENT_KEY = "agentKey"
    private const val PREF_NAME = "meshagent"

    fun ensureAgentCertificate(context: Context) {
        if ((agentCertificate != null) && (agentCertificateKey != null)) return

        val sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val certb64 = sharedPreferences?.getString(PREF_AGENT_CERT, null)
        val keyb64 = sharedPreferences?.getString(PREF_AGENT_KEY, null)

        if ((certb64 != null) && (keyb64 != null)) {
            loadFromPreferences(certb64, keyb64)
            return
        }

        generateAndStoreCertificate(sharedPreferences)
    }

    private fun loadFromPreferences(certb64: String, keyb64: String) {
        agentCertificate = CertificateFactory.getInstance("X509").generateCertificate(
            ByteArrayInputStream(Base64.decode(certb64, Base64.DEFAULT))
        ) as X509Certificate
        val keySpec = PKCS8EncodedKeySpec(Base64.decode(keyb64, Base64.DEFAULT))
        agentCertificateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun generateAndStoreCertificate(sharedPreferences: android.content.SharedPreferences?) {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keypair = keyGen.generateKeyPair()

        var serial = BigInteger("12345678")
        try {
            serial = BigInteger.valueOf(Random().nextInt().toLong().absoluteValue)
        } catch (_: Exception) {
        }

        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
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

        sharedPreferences?.edit()?.putString(PREF_AGENT_CERT, Base64.encodeToString(agentCertificate?.encoded, Base64.DEFAULT))?.apply()
        sharedPreferences?.edit()?.putString(PREF_AGENT_KEY, Base64.encodeToString(agentCertificateKey?.encoded, Base64.DEFAULT))?.apply()
    }
}
