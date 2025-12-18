package com.meshcentral.agent

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
//import org.webrtc.PeerConnectionFactory
import java.io.*
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.collections.ArrayList
import kotlin.math.absoluteValue
import kotlin.random.Random


class PendingActivityData(tunnel: MeshTunnel, id: Int, url: Uri, where: String, args: String, req: JSONObject) {
    var tunnel : MeshTunnel = tunnel
    var id : Int = id
    var url : Uri = url
    var where : String = where
    var args : String = args
    var req : JSONObject = req
}

class MeshTunnel(private var parent: MeshAgent, private var url: String, private var serverData: JSONObject) : WebSocketListener() {
    private var serverTlsCertHash: ByteArray? = null
    private var connectionTimer: CountDownTimer? = null
    var _webSocket: WebSocket? = null
    var state: Int = 0 // 0 = Disconnected, 1 = Connecting, 2 = Connected
    var usage: Int = 0 // 2 = Desktop, 5 = Files, 10 = File transfer
    private var tunnelOptions : JSONObject? = null
    private var lastDirRequest : JSONObject? = null
    private var fileUpload : OutputStream? = null
    private var fileUploadName : String? = null
    private var fileUploadReqId : Int = 0
    private var fileUploadSize : Int = 0
    var userid : String? = null
    var guestname : String? = null
    var sessionUserName : String? = null // UserID + GuestName in Base64 if this is a shared session.
    var sessionUserName2 : String? = null // UserID/GuestName

    init { }

    private val logTag = "MeshTunnel"

    fun Start() {
        //println("MeshTunnel Init: ${serverData.toString()}")
        val serverTlsCertHashHex = serverData.optString("servertlshash")
        serverTlsCertHash = parent.hexToByteArray(serverTlsCertHashHex)
        //var tunnelUsage = serverData.getInt("usage")
        //var tunnelUser = serverData.getString("username")

        // Set the userid and request more data about this user
        guestname = serverData.optString("guestname")
        userid = serverData.optString("userid")
        if (userid != null) parent.sendUserImageRequest(userid!!)
        sessionUserName = userid
        sessionUserName2 = userid
        if ((userid != "") && (guestname != "")) {
            sessionUserName = userid + "/guest:" + Base64.encodeToString(guestname!!.toByteArray(), Base64.NO_WRAP)
            sessionUserName2 = "$userid/$guestname"
        }

        //println("Starting tunnel: $url")
        //println("Tunnel usage: $tunnelUsage")
        //println("Tunnel user: $tunnelUser")
        //println("Tunnel userid: $userid")
        //println("Tunnel sessionUserName: $sessionUserName")
        //println("Tunnel sessionUserName2: $sessionUserName2")
        startSocket()
    }

    fun Stop() {
        //println("MeshTunnel Stop")
        stopSocket()
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        // Create a trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
            ) {
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val encoded = chain?.get(0)?.encoded ?: throw CertificateException("No certificate")
                val hash = MessageDigest.getInstance("SHA-384").digest(encoded).toHex()
                if ((serverTlsCertHash != null) && (hash == serverTlsCertHash?.toHex())) return
                if (hash == parent.serverTlsCertHash?.toHex()) return
                println("Got Bad Tunnel TlsHash: $hash")
                throw CertificateException()
            }

            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })

        // Install the special trust manager that records the certificate hash of the server
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.MINUTES)
                .writeTimeout(60, TimeUnit.MINUTES)
                .hostnameVerifier ( hostnameVerifier = HostnameVerifier{ _, _ -> true })
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .build()
    }


    fun startSocket() {
        _webSocket = getUnsafeOkHttpClient().newWebSocket(
                Request.Builder().url(url).build(),
                this
        )
    }

    fun stopSocket() {
        // Disconnect and clean the relay socket
        if (_webSocket != null) {
            try {
                _webSocket?.close(NORMAL_CLOSURE_STATUS, null)
                _webSocket = null
            } catch (_: Exception) { }
        }
        // Clear the connection timer
        if (connectionTimer != null) {
            connectionTimer?.cancel()
            connectionTimer = null
        }
        // Remove the tunnel from the parent's list
        parent.removeTunnel(this) // Notify the parent that this tunnel is done

        // Check if there are no more remote desktop tunnels
        if ((usage == 2) && (g_ScreenCaptureService != null)) {
            g_ScreenCaptureService!!.checkNoMoreDesktopTunnels()
        }
    }

    fun sendCtrlResponse(values: JSONObject?) {
        val json = JSONObject()
        json.put("ctrlChannel", "102938")
        values?.let {
            for (key in it.keys()) {
                json.put(key, it.get(key))
            }
        }
        if (_webSocket != null) { _webSocket?.send(json.toString()) }
    }

    companion object {
        const val NORMAL_CLOSURE_STATUS = 1000
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        //println("Tunnel-onOpen")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        //println("Tunnel-onMessage: $text")
        if (state == 0) {
            if ((text == "c") || (text == "cr")) { state = 1; }
            return
        }
        else if (state == 1) {
            // {"type":"options","file":"Images/1104105516.JPG"}
            if (text.startsWith('{')) {
                val json = JSONObject(text)
                val type = json.optString("type")
                if (type == "options") { tunnelOptions = json }
            } else {
                val xusage = text.toInt()
                if (((xusage < 1) || (xusage > 5)) && (xusage != 10)) {
                    println("Invalid usage $text"); stopSocket(); return
                }
                val serverExpectedUsage = serverData.optInt("usage")
                if ((serverExpectedUsage != 0) && (serverExpectedUsage != xusage)) {
                    println("Unexpected usage $text != $serverExpectedUsage");
                    stopSocket(); return
                }
                usage = xusage; // 2 = Desktop, 5 = Files, 10 = File transfer
                state = 2

                // Start the connection time except if this is a file transfer
                if (usage != 10) {
                    //println("Connected usage $usage")
                    startConnectionTimer()
                    if (usage == 2) {
                        // If this is a remote desktop usage...
                        if (!g_autoConsent && g_ScreenCaptureService == null) {
                            // asking for consent
                            if (meshAgent?.tunnels?.getOrNull(0) != null) {
                                val json = JSONObject()
                                json.put("type", "console")
                                json.put("msg", "Waiting for user to grant access...")
                                json.put("msgid", 1)
                                meshAgent!!.tunnels[0].sendCtrlResponse(json)
                            }
                        }
                        if (g_ScreenCaptureService == null) {
                            // Request media projection
                            parent.parent.startProjection()
                        } else {
                            if (meshAgent?.tunnels?.getOrNull(0) != null) {
                                val json = JSONObject()
                                json.put("type", "console")
                                json.put("msg", null)
                                json.put("msgid", 0)
                                meshAgent!!.tunnels[0].sendCtrlResponse(json)
                            }
                            // Send the display size
                            updateDesktopDisplaySize()
                        }
                    }
                } else {
                    // This is a file transfer
                    if (tunnelOptions == null) {
                        println("No file transfer options");
                        stopSocket();
                    } else {
                        val filename = tunnelOptions?.optString("file")
                        if (filename == null) {
                            println("No file transfer name");
                            stopSocket();
                        } else {
                            //println("File transfer usage")
                            startFileTransfer(filename)
                        }
                    }
                }
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        //println("Tunnel-onBinaryMessage: ${bytes.size}, ${bytes.toByteArray().toHex()}")
        if ((state != 2) || (bytes.size < 2)) return;
        try {
            if (bytes[0].toInt() == 123) {
                // If we are authenticated, process JSON data
                processTunnelData(String(bytes.toByteArray(), Charsets.UTF_8))
            } else if (fileUpload != null) {
                // If this is file upload data, process it here
                if (bytes[0].toInt() == 0) {
                    // If data starts with zero, skip the first byte. This is used to escape binary file data from JSON.
                    fileUploadSize += (bytes.size - 1);
                    val buf = bytes.toByteArray()
                    try {
                        fileUpload?.write(buf, 1, buf.size - 1)
                    } catch (_ : Exception) {
                        // Report a problem
                        uploadError()
                        return
                    }
                } else {
                    // If data does not start with zero, save as-is.
                    fileUploadSize += bytes.size;
                    try {
                        fileUpload?.write(bytes.toByteArray())
                    } catch (_ : Exception) {
                        // Report a problem
                        uploadError()
                        return
                    }
                }

                // Ask for more data
                val json = JSONObject()
                json.put("action", "uploadack")
                json.put("reqid", fileUploadReqId)
                if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
            } else {
                if (bytes.size < 2) return
                val cmd : Int = (bytes[0].toInt() shl 8) + bytes[1].toInt()
                val cmdsize : Int = (bytes[2].toInt() shl 8) + bytes[3].toInt()
                if (cmdsize != bytes.size) return
                //println("Cmd $cmd, Size: ${bytes.size}, Hex: ${bytes.toByteArray().toHex()}")
                if (usage == 2) processBinaryDesktopCmd(cmd, cmdsize, bytes) // Remote desktop
            }
        }
        catch (e: Exception) {
            println("Tunnel-Exception: ${e.toString()}")
        }
    }

    private fun processBinaryDesktopCmd(cmd : Int, cmdsize: Int, msg: ByteString) {
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "desktop cmd=$cmd size=$cmdsize")
        }
        when (cmd) {
            1 -> handleLegacyKeyboardInput(msg)
            2 -> handleMouseInput(msg)
            5 -> { // Remote Desktop Settings
                if (cmdsize < 6) return
                g_desktop_imageType = msg[4].toInt() // 1 = JPEG, 2 = PNG, 3 = TIFF, 4 = WebP. TIFF is not support on Android.
                g_desktop_compressionLevel = msg[5].toInt() // Value from 1 to 100
                if (cmdsize >= 8) { g_desktop_scalingLevel = (msg[6].toInt() shl 8).absoluteValue + msg[7].toInt().absoluteValue } // 1024 = 100%
                if (cmdsize >= 10) { g_desktop_frameRateLimiter = (msg[8].toInt() shl 8).absoluteValue + msg[9].toInt().absoluteValue }
                println("Desktop Settings, type=$g_desktop_imageType, comp=$g_desktop_compressionLevel, scale=$g_desktop_scalingLevel, rate=$g_desktop_frameRateLimiter")
                updateDesktopDisplaySize()
            }
            6 -> { // Refresh
                // Nop
                println("Desktop Refresh")
            }
            8 -> { // Pause
                // Nop
            }
            85 -> handleUnicodeKeyboardInput(msg)
            87 -> handleRemoteInputLock(msg)
            else -> {
                println("Unknown desktop binary command: $cmd, Size: ${msg.size}, Hex: ${msg.toByteArray().toHex()}")
            }
        }
    }

    private fun handleLegacyKeyboardInput(msg: ByteString) {
        val data = msg.toByteArray()
        val payloadOffset = 4
        if (data.size < payloadOffset + 2) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "legacy key data too short (${data.size}) hex=${data.toHex()}")
            }
            return
        }
        val actionFlag = data[payloadOffset].toInt() and 0xFF
        val remoteCode = data[payloadOffset + 1].toInt() and 0xFF
        val action = when (actionFlag) {
            0, 3 -> KeyEvent.ACTION_DOWN
            1, 4 -> KeyEvent.ACTION_UP
            else -> return
        }
        val remoteChar = if (remoteCode in 32..126) " ('${remoteCode.toChar()}')" else ""
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "legacy key raw remote=$remoteCode$remoteChar actionFlag=$actionFlag action=$action")
        }
        val mappedKey = mapRemoteKeyCode(remoteCode)
        if (mappedKey == null) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "legacy key remote=$remoteCode unmapped action=$action")
            }
            return
        }
        val (keyCode, meta) = mappedKey
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "legacy key mapped keyCode=${KeyEvent.keyCodeToString(keyCode)}($keyCode) action=$action meta=$meta")
        }
        val service = MeshInputAccessibilityService.instance
        if (service == null) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "legacy key dropped because accessibility service is null")
            }
            return
        }
        service.injectKey(keyCode, action, meta)
    }

    private fun handleUnicodeKeyboardInput(msg: ByteString) {
        val data = msg.toByteArray()
        val payloadOffset = 4
        if (data.size < payloadOffset + 3) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "unicode key data too short (${data.size}) hex=${data.toHex()}")
            }
            return
        }
        val actionFlag = data[payloadOffset].toInt() and 0xFF
        val charCode = ((data[payloadOffset + 1].toInt() and 0xFF) shl 8) or (data[payloadOffset + 2].toInt() and 0xFF)
        val action = if (actionFlag == 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "unicode key raw charCode=$charCode actionFlag=$actionFlag action=$action")
        }
        val unicodeMapping = mapUnicodeChar(charCode)
        if (unicodeMapping == null) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "unicode key char=$charCode unmapped action=$action")
            }
            return
        }
        val (keyCode, meta) = unicodeMapping
        val unicodeChar = if (charCode in 32..0x10FFFF) " ('${charCode.toChar()}')" else ""
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "unicode key mapped keyCode=${KeyEvent.keyCodeToString(keyCode)}($keyCode)$unicodeChar action=$action meta=$meta")
        }
        val service = MeshInputAccessibilityService.instance
        if (service == null) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "unicode key dropped because accessibility service is null")
            }
            return
        }
        service.injectKey(keyCode, action, meta)
    }

    private fun handleRemoteInputLock(msg: ByteString) {
        val data = msg.toByteArray()
        val offset = 4
        if (data.size <= offset + 4) return
        val locked = (data[offset + 4].toInt() and 0xFF) != 0
        MeshInputAccessibilityService.instance?.setRemoteInputLocked(locked)
    }

    private fun handleMouseInput(msg: ByteString) {
        val data = msg.toByteArray()
        val offset = 4
        val payloadLen = data.size - offset
        if (payloadLen < 6) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "mouse data too short (${data.size}) hex=${data.toHex()}")
            }
            return
        }
        val service = MeshInputAccessibilityService.instance
        if (service == null) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "mouse button event dropped because accessibility service unavailable")
            }
            return
        }
        val button = data[offset + 1].toInt() and 0xFF
        val x = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
        val y = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
        if (payloadLen >= 8) {
            val delta = decodeScrollDelta(data[offset + 6].toInt(), data[offset + 7].toInt())
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "mouse scroll x=$x y=$y delta=$delta")
            }
            MeshInputAccessibilityService.instance?.injectMouseScroll(x, y, delta)
            return
        }
        val (buttonAction, buttonName) = when (button) {
            2 -> "down" to "left"
            4 -> "up" to "left"
            8 -> "down" to "right"
            16 -> "up" to "right"
            32 -> "down" to "middle"
            64 -> "up" to "middle"
            136 -> "double" to "double"
            else -> "move" to "unknown"
        }
        when (button) {
            0 -> {
                if (BuildConfig.DEBUG) {
                    Log.d(logTag, "mouse move x=$x y=$y")
                }
                service.injectMouseMove(x, y)
            }
            2, 8, 32 -> {
                if (BuildConfig.DEBUG) {
                    Log.d(logTag, "mouse $buttonAction ($buttonName) x=$x y=$y button=$button")
                }
                service.injectMouseDown(x, y)
            }
            4, 16, 64 -> {
                if (BuildConfig.DEBUG) {
                    Log.d(logTag, "mouse $buttonAction ($buttonName) x=$x y=$y button=$button")
                }
                service.injectMouseUp(x, y)
            }
            136 -> {
                if (BuildConfig.DEBUG) {
                    Log.d(logTag, "mouse double click ($buttonName) x=$x y=$y button=$button")
                }
                service.injectMouseDoubleClick(x, y)
            }
            else -> {
                if (BuildConfig.DEBUG) {
                    Log.d(logTag, "mouse default move x=$x y=$y button=$button")
                }
                service.injectMouseMove(x, y)
            }
        }
    }

    private fun decodeScrollDelta(high: Int, low: Int): Int {
        val value = ((high and 0xFF) shl 8) or (low and 0xFF)
        return if (value >= 0x8000) value - 0xFFFF else value
    }

    private fun mapRemoteKeyCode(remoteCode: Int): Pair<Int, Int>? {
        return when (remoteCode) {
            in 65..90 -> Pair(KeyEvent.KEYCODE_A + (remoteCode - 65), 0)
            in 48..57 -> Pair(KeyEvent.KEYCODE_0 + (remoteCode - 48), 0)
            in 96..105 -> Pair(KeyEvent.KEYCODE_NUMPAD_0 + (remoteCode - 96), 0)
            in 112..123 -> Pair(KeyEvent.KEYCODE_F1 + (remoteCode - 112), 0)
            8 -> Pair(KeyEvent.KEYCODE_DEL, 0)
            9 -> Pair(KeyEvent.KEYCODE_TAB, 0)
            13 -> Pair(KeyEvent.KEYCODE_ENTER, 0)
            16 -> Pair(KeyEvent.KEYCODE_SHIFT_LEFT, 0)
            17 -> Pair(KeyEvent.KEYCODE_CTRL_LEFT, 0)
            18 -> Pair(KeyEvent.KEYCODE_ALT_LEFT, 0)
            20 -> Pair(KeyEvent.KEYCODE_CAPS_LOCK, 0)
            27 -> Pair(KeyEvent.KEYCODE_ESCAPE, 0)
            32 -> Pair(KeyEvent.KEYCODE_SPACE, 0)
            35 -> Pair(KeyEvent.KEYCODE_MOVE_END, 0)
            36 -> Pair(KeyEvent.KEYCODE_MOVE_HOME, 0)
            45 -> Pair(KeyEvent.KEYCODE_INSERT, 0)
            46 -> Pair(KeyEvent.KEYCODE_FORWARD_DEL, 0)
            91 -> Pair(KeyEvent.KEYCODE_META_LEFT, 0)
            92 -> Pair(KeyEvent.KEYCODE_META_RIGHT, 0)
            93 -> Pair(KeyEvent.KEYCODE_MENU, 0)
            144 -> Pair(KeyEvent.KEYCODE_NUM_LOCK, 0)
            145 -> Pair(KeyEvent.KEYCODE_SCROLL_LOCK, 0)
            186 -> Pair(KeyEvent.KEYCODE_SEMICOLON, 0)
            187 -> Pair(KeyEvent.KEYCODE_EQUALS, 0)
            188 -> Pair(KeyEvent.KEYCODE_COMMA, 0)
            189 -> Pair(KeyEvent.KEYCODE_MINUS, 0)
            190 -> Pair(KeyEvent.KEYCODE_PERIOD, 0)
            191 -> Pair(KeyEvent.KEYCODE_SLASH, 0)
            192 -> Pair(KeyEvent.KEYCODE_GRAVE, 0)
            219 -> Pair(KeyEvent.KEYCODE_LEFT_BRACKET, 0)
            220 -> Pair(KeyEvent.KEYCODE_BACKSLASH, 0)
            221 -> Pair(KeyEvent.KEYCODE_RIGHT_BRACKET, 0)
            222 -> Pair(KeyEvent.KEYCODE_APOSTROPHE, 0)
            else -> null
        }
    }

    private fun mapUnicodeChar(charCode: Int): Pair<Int, Int>? {
        val char = charCode.toChar()
        return when (char) {
            in 'a'..'z' -> Pair(KeyEvent.KEYCODE_A + (char - 'a'), 0)
            in 'A'..'Z' -> Pair(KeyEvent.KEYCODE_A + (char - 'A'), KeyEvent.META_SHIFT_ON)
            in '0'..'9' -> Pair(KeyEvent.KEYCODE_0 + (char - '0'), 0)
            ' ' -> Pair(KeyEvent.KEYCODE_SPACE, 0)
            '\n', '\r' -> Pair(KeyEvent.KEYCODE_ENTER, 0)
            '\t' -> Pair(KeyEvent.KEYCODE_TAB, 0)
            ',' -> Pair(KeyEvent.KEYCODE_COMMA, 0)
            '.' -> Pair(KeyEvent.KEYCODE_PERIOD, 0)
            ';' -> Pair(KeyEvent.KEYCODE_SEMICOLON, 0)
            ':' -> Pair(KeyEvent.KEYCODE_SEMICOLON, KeyEvent.META_SHIFT_ON)
            '+' -> Pair(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_SHIFT_ON)
            '-' -> Pair(KeyEvent.KEYCODE_MINUS, 0)
            '_' -> Pair(KeyEvent.KEYCODE_MINUS, KeyEvent.META_SHIFT_ON)
            '=' -> Pair(KeyEvent.KEYCODE_EQUALS, 0)
            '/' -> Pair(KeyEvent.KEYCODE_SLASH, 0)
            '?' -> Pair(KeyEvent.KEYCODE_SLASH, KeyEvent.META_SHIFT_ON)
            '@' -> Pair(KeyEvent.KEYCODE_2, KeyEvent.META_SHIFT_ON)
            '#' -> Pair(KeyEvent.KEYCODE_3, KeyEvent.META_SHIFT_ON)
            '$' -> Pair(KeyEvent.KEYCODE_4, KeyEvent.META_SHIFT_ON)
            '%' -> Pair(KeyEvent.KEYCODE_5, KeyEvent.META_SHIFT_ON)
            '^' -> Pair(KeyEvent.KEYCODE_6, KeyEvent.META_SHIFT_ON)
            '&' -> Pair(KeyEvent.KEYCODE_7, KeyEvent.META_SHIFT_ON)
            '*' -> Pair(KeyEvent.KEYCODE_8, KeyEvent.META_SHIFT_ON)
            '(' -> Pair(KeyEvent.KEYCODE_9, KeyEvent.META_SHIFT_ON)
            ')' -> Pair(KeyEvent.KEYCODE_0, KeyEvent.META_SHIFT_ON)
            '!' -> Pair(KeyEvent.KEYCODE_1, KeyEvent.META_SHIFT_ON)
            else -> null
        }
    }

    fun updateDesktopDisplaySize() {
        if ((g_ScreenCaptureService == null) || (_webSocket == null)) return
        //println("updateDesktopDisplaySize: ${g_ScreenCaptureService!!.mWidth} x ${g_ScreenCaptureService!!.mHeight}")

        // Get the display size
        var mWidth : Int = g_ScreenCaptureService!!.mWidth
        var mHeight : Int = g_ScreenCaptureService!!.mHeight

        // Scale the display if needed
        if (g_desktop_scalingLevel != 1024) {
            mWidth = (mWidth * g_desktop_scalingLevel) / 1024
            mHeight = (mHeight * g_desktop_scalingLevel) / 1024
        }

        // Send the display size command
        val bytesOut = ByteArrayOutputStream()
        DataOutputStream(bytesOut).use { dos ->
            with(dos) {
                writeShort(7) // Screen size command
                writeShort(8) // Screen size command size
                writeShort(mWidth) // Width
                writeShort(mHeight) // Height
            }
        }
        _webSocket!!.send(bytesOut.toByteArray().toByteString())
    }

    // Cause some data to be sent over the websocket control channel every 2 minutes to keep it open
    private fun startConnectionTimer() {
        parent.parent.runOnUiThread {
            connectionTimer = object: CountDownTimer(120000000, 120000) {
                override fun onTick(millisUntilFinished: Long) {
                    _webSocket?.send(ByteArray(1).toByteString()) // If not, sent a single zero byte
                }
                override fun onFinish() { startConnectionTimer() }
            }
            connectionTimer?.start()
        }
    }

    private fun uploadError() {
        val json = JSONObject()
        json.put("action", "uploaderror")
        json.put("reqid", fileUploadReqId)
        _webSocket?.send(json.toString().toByteArray().toByteString())
        try { fileUpload?.close() } catch (_ : Exception) {}
        fileUpload = null
    }

    private fun processTunnelData(jsonStr: String) {
        //println("JSON: $jsonStr")
        val json = JSONObject(jsonStr)
        val action = json.getString("action")
        //println("action: $action")
        when (action) {
            "ls" -> {
                val path = json.getString("path")
                if (path == "") {
                    val r: JSONArray = JSONArray()
                    r.put(JSONObject("{n:\"Sdcard\",t:2}"))
                    r.put(JSONObject("{n:\"Images\",t:2}"))
                    r.put(JSONObject("{n:\"Audio\",t:2}"))
                    r.put(JSONObject("{n:\"Videos\",t:2}"))
                    //r.put(JSONObject("{n:\"Documents\",t:2}"))
                    json.put("dir", r)
                } else {
                    lastDirRequest = json; // Bit of a hack, but use this to refresh after a file delete
                    json.put("dir", getFolder(path))
                }
                _webSocket?.send(json.toString().toByteArray(Charsets.UTF_8).toByteString())
            }
            "rm" -> {
                val path = json.getString("path")
                val filenames = json.getJSONArray("delfiles")
                deleteFile(path, filenames, json)
            }
            "upload" -> {
                // {"action":"upload","reqid":0,"path":"Images","name":"00000000.JPG","size":1180231}
                val path = json.getString("path")
                val name = json.getString("name")
                //val size = json.getInt("size")
                val reqid = json.getInt("reqid")

                // Close previous upload
                if (fileUpload != null) {
                    fileUpload?.close()
                    fileUpload = null;
                }

                // Setup
                fileUploadName = name
                fileUploadReqId = reqid
                fileUploadSize = 0

                if (path.startsWith("Sdcard")) {
                    val fileDir: String = path.replaceFirst("Sdcard", Environment.getExternalStorageDirectory().absolutePath)
                    val file = File(fileDir, name)
                    try {
                        fileUpload = FileOutputStream(file)
                    } catch (e: Exception) {
                        uploadError()
                        return
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver: ContentResolver = parent.parent.contentResolver
                        val contentValues = ContentValues()
                        val fileUri: Uri?
                        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        val (mimeType, relativePath, externalUri) = when {
                            name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") -> Triple("image/jpg", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".png") -> Triple("image/png", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".bmp") -> Triple("image/bmp", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".mp4") -> Triple("video/mp4", Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".mp3") -> Triple("audio/mpeg3", Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                            name.lowercase().endsWith(".ogg") -> Triple("audio/ogg", Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                            else -> {
                                println("Unsupported file type: $name")
                                Triple(null, null, null)
                            }
                        }
                        if (mimeType != null && relativePath != null && externalUri != null) {
                            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                            fileUri = resolver.insert(externalUri, contentValues)
                            try {
                                fileUpload = resolver.openOutputStream(fileUri!!)
                            } catch (e: Exception) {
                                uploadError()
                                return
                            }
                        } else {
                            uploadError()
                            return
                        }
                    } else {
                        val fileExtension = name.lowercase().substringAfterLast('.')
                        val fileDir: String = when (fileExtension) {
                            "jpg", "jpeg", "png" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString()
                            "mp4", "mkv" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).toString()
                            "mp3", "wav" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).toString()
                            else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString()
                        }
                        val file = File(fileDir, name)
                        try {
                            fileUpload = FileOutputStream(file)
                        } catch (e: Exception) {
                            uploadError()
                            return
                        }
                    }
                }

                // Send response
                val jsonResponse = JSONObject()
                jsonResponse.put("action", "uploadstart")
                jsonResponse.put("reqid", reqid)
                if (_webSocket != null) { _webSocket?.send(jsonResponse.toString().toByteArray().toByteString()) }
            }
            "uploaddone" -> {
                if (fileUpload == null) return;
                fileUpload?.close()
                fileUpload = null;

                // Send response
                val jsonResponse = JSONObject()
                jsonResponse.put("action", "uploaddone")
                jsonResponse.put("reqid", fileUploadReqId)
                if (_webSocket != null) { _webSocket?.send(jsonResponse.toString().toByteArray().toByteString()) }

                // Event the server
                val eventArgs = JSONArray()
                eventArgs.put(fileUploadName)
                eventArgs.put(fileUploadSize)
                parent.logServerEventEx(105, eventArgs, "Upload: \"${fileUploadName}}\", Size: $fileUploadSize", serverData);
            }
            else -> {
                // Unknown command, ignore it.
                println("Unhandled action: $action, $jsonStr")
            }
        }
    }

    // https://developer.android.com/training/data-storage/shared/media
    fun getFolder(dir: String) : JSONArray {
        val r : JSONArray = JSONArray()
        val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
        )
        var uri : Uri? = null;
        if (dir.startsWith("Sdcard")) { uri = Uri.fromFile(Environment.getExternalStorageDirectory()) }
        if (dir == "Images") { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (dir == "Audio") { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (dir == "Videos") { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (dir == "Documents") { uri = MediaStore.Files. }
        if (uri == null) { return r }
        if (dir.startsWith("Sdcard")) {
            val path = dir.replaceFirst("Sdcard", Environment.getExternalStorageDirectory().absolutePath)
            val listOfFiles = File(path).listFiles() ?: emptyArray()
            for (file in listOfFiles) {
                val f = JSONObject()
                f.put("n", file.name)
                if (file.isDirectory) f.put("t", 2)
                else f.put("t", 3)
                //f.put("t", 3)
                f.put("s", file.length())
                f.put("d", file.lastModified())
                r.put(f)
            }
        } else {
        val cursor: Cursor? = parent.parent.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
        )
        if (cursor != null) {
            val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateModified: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            //val typeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val f = JSONObject()
                f.put("n", cursor.getString(titleColumn))
                f.put("t", 3)
                f.put("s", cursor.getInt(sizeColumn))
                f.put("d", cursor.getInt(dateModified))
                r.put(f)
                //println("${cursor.getString(titleColumn)}, ${cursor.getString(typeColumn)}")
                }
            }
        }
        return r;
    }

    fun deleteFile(path: String, filenames: JSONArray, req: JSONObject) {
        val fileArray:ArrayList<String> = ArrayList<String>()
        for (i in 0 until filenames.length()) { fileArray.add(filenames.getString(i)) }

        val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
        )
        var uri : Uri? = null;
        if (path.startsWith("Sdcard")) { uri = Uri.fromFile(Environment.getExternalStorageDirectory()) }
        if (path == "Images") { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (path == "Audio") { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (path == "Videos") { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (filenameSplit[0] == "Documents") { uri = MediaStore.Files. }
        if (uri == null) return

        if (path.startsWith("Sdcard")) {
            val filePath = path.replaceFirst("Sdcard", Environment.getExternalStorageDirectory().absolutePath)
            try {
                for (i in 0 until filenames.length())
                {
                    fileArray.add(filenames.getString(i))
                    val file = File(filePath + "/" + filenames.getString(i))
                    if (file.exists()) {
                        if(file.delete()){
                            fileDeleteResponse(req, true) // Send success
                        } else {
                            fileDeleteResponse(req, false) // Send failure
                        }
                    } else {
                        fileDeleteResponse(req, false) // Send failure, file not found
                    }
                }
            } catch (securityException: SecurityException) {
                fileDeleteResponse(req, false) // Send failure
            }
        } else {
            val cursor: Cursor? = parent.parent.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )
            if (cursor != null) {
                val idColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                //val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val fileidArray:ArrayList<String> = ArrayList<String>()
                val fileUriArray:ArrayList<Uri> = ArrayList<Uri>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(titleColumn)
                    if (fileArray.contains(name)) {
                        val id = cursor.getString(idColumn)
                        val contentUrl: Uri = ContentUris.withAppendedId(uri, cursor.getLong(idColumn))
                        //val fileSize = cursor.getInt(sizeColumn)
                        fileidArray.add(id)
                        fileUriArray.add(contentUrl)
                    }
                }
                for (i in 0 until filenames.length()) {
                    try {
                        parent.parent.contentResolver.delete(fileUriArray[i],null,null)
                        fileDeleteResponse(req, true) // Send success
                    } catch (securityException: SecurityException) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val recoverableSecurityException =
                                securityException as? RecoverableSecurityException
                                    ?: throw securityException

                            // Create pending activity data
                            val activityCode = Random.nextInt() and 0xFFFF
                            val pad = PendingActivityData(this, activityCode, fileUriArray[0], "${MediaStore.Images.Media._ID} = ?", fileidArray[0], req)

                            // Launch the activity using the modern Activity Result API
                            val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
                            parent.parent.launchIntentSenderForResult(intentSender, pad)
                        } else {
                            fileDeleteResponse(req, false) // Send fail
                        }
                    }
                }
            }
        }
    }

    fun deleteFileEx(pad: PendingActivityData) {
        try {
            parent.parent.contentResolver.delete(pad.url, pad.where, arrayOf(pad.args))
            fileDeleteResponse(pad.req, true) // Send success
        } catch (ex: Exception) {
            fileDeleteResponse(pad.req, false) // Send fail
        }
    }

    fun startFileTransfer(filename: String) {
        val filenameSplit = filename.split('/')
        //println("startFileTransfer: $filenameSplit")

        val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
        )
        var uri : Uri? = null;
        if (filenameSplit[0].startsWith("Sdcard")) { uri = Uri.fromFile(Environment.getExternalStorageDirectory()) }
        if (filenameSplit[0] == "Images") { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (filenameSplit[0] == "Audio") { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (filenameSplit[0] == "Videos") { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (filenameSplit[0] == "Documents") { uri = MediaStore.Files. }
        if (uri == null) { stopSocket(); return }
        if (filenameSplit[0].startsWith("Sdcard")){
            val path = filename.replaceFirst("Sdcard", Environment.getExternalStorageDirectory().absolutePath)
            val file = File(path)
            if (file.exists()) {
                val fileName = file.name
                val fileSize = file.length()
                val eventArgs = JSONArray()
                eventArgs.put(fileName)
                eventArgs.put(fileSize)
                parent.logServerEventEx(106, eventArgs, "Download: $fileName, Size: $fileSize", serverData)
                val contentUrl = Uri.fromFile(file)
                try {
                    // Serve the file
                    parent.parent.contentResolver.openInputStream(contentUrl).use { stream ->
                            // Perform operation on stream
                            val buf = ByteArray(65535)
                            var len : Int
                            while (true) {
                                len = stream!!.read(buf, 0, 65535)
                                if (len <= 0) { stopSocket(); break; } // Stream is done
                                if (_webSocket == null) { stopSocket(); break; } // Web socket closed
                                _webSocket?.send(buf.toByteString(0, len))
                                if (_webSocket?.queueSize()!! > 655350) { Thread.sleep(100)}
                            }
                        }
                    return
                } catch (e: FileNotFoundException) {
                    // file not found
                }
            } else {
                // file does not exist
            }
        } else {
            val cursor: Cursor? = parent.parent.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    null
            )
            if (cursor != null) {
                val idColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(titleColumn)
                    if (name == filenameSplit[1]) {
                        val contentUrl: Uri = ContentUris.withAppendedId(uri, cursor.getLong(idColumn))
                        val fileSize = cursor.getInt(sizeColumn)

                        // Event to the server
                        val eventArgs = JSONArray()
                        eventArgs.put(filename)
                        eventArgs.put(fileSize)
                        parent.logServerEventEx(106, eventArgs, "Download: $filename, Size: $fileSize", serverData)

                        // Serve the file
                        parent.parent.contentResolver.openInputStream(contentUrl).use { stream ->
                            // Perform operation on stream
                            val buf = ByteArray(65535)
                            var len : Int
                            while (true) {
                                len = stream!!.read(buf, 0, 65535)
                                if (len <= 0) { stopSocket(); break; } // Stream is done
                                if (_webSocket == null) { stopSocket(); break; } // Web socket closed
                                _webSocket?.send(buf.toByteString(0, len))
                                if (_webSocket?.queueSize()!! > 655350) { Thread.sleep(100)}
                            }
                        }
                        return
                    }
                }
            }
        }
        stopSocket()
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        //println("Tunnel-onClosing")
        stopSocket()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        println("Tunnel-onFailure ${t.toString()},  ${response.toString()}")
        stopSocket()
    }

    fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    fun fileDeleteResponse(req: JSONObject, success: Boolean) {
        val json = JSONObject()
        json.put("action", "rm")
        json.put("reqid", req.getString("reqid"))
        json.put("success", success)
        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }

        // Event to the server
        val path = req.getString("path")
        val filenames = req.getJSONArray("delfiles")
        if (filenames.length() == 1) {
            val eventArgs = JSONArray()
            eventArgs.put(path + '/' + filenames[0])
            parent.logServerEventEx(45, eventArgs, "Delete: \"${path}/${filenames[0]}\"", serverData);
        }

        if (success && (lastDirRequest != null)) {
            val path = lastDirRequest?.getString("path")
            if ((path != null) && (path != "")) {
                lastDirRequest?.put("dir", getFolder(path))
                if (_webSocket != null) {_webSocket?.send(lastDirRequest?.toString()!!.toByteArray(Charsets.UTF_8).toByteString()) }
            }
        }
    }

    // WebRTC setup
    /*
    private fun initializePeerConnectionFactory() {
        //Initialize PeerConnectionFactory globals.
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(parent.parent).createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        //Create a new PeerConnectionFactory instance - using Hardware encoder and decoder.
        val options = PeerConnectionFactory.Options()
        //val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(rootEglBase?.eglBaseContext,  /* enableIntelVp8Encoder */true,  /* enableH264HighProfile */true)
        //val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext)
        val factory = PeerConnectionFactory.builder()
                .setOptions(options)
                //.setVideoEncoderFactory(defaultVideoEncoderFactory)
                //.setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory()

        //factory.createPeerConnection()
    }
    */

}
