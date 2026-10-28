package com.goldenswordllc.triad.net

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import triad.core.*
import triad.net.*
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

fun lanIp(): String? = NetworkInterface.getNetworkInterfaces().asSequence()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.inetAddresses.asSequence() }
    .filterIsInstance<Inet4Address>()
    .firstOrNull { !it.isLoopbackAddress }?.hostAddress

fun isOnWifi(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
             as? android.net.ConnectivityManager ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
}

fun qrBitmap(content: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (x in 0 until size) for (y in 0 until size)
            setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
    }
}

data class QrInvite(val transport: String, val ip: String?, val port: Int?, val name: String, val token: String)

fun parseInvite(raw: String): QrInvite? = runCatching {
    val uri = Uri.parse(raw)
    check(uri.scheme == "triad" && uri.host == "v1")
    QrInvite(
        transport = uri.getQueryParameter("tr") ?: "tcp",
        ip = uri.getQueryParameter("ip"),
        port = uri.getQueryParameter("port")?.toInt(),
        name = uri.getQueryParameter("name") ?: "?",
        token = uri.getQueryParameter("t") ?: "")
}.getOrNull()

class HostViewModel(private val app: Application, private val catalog: Map<String, CardDef>) : AndroidViewModel(app) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val qrPayload = MutableStateFlow<String?>(null)
    val session = MutableStateFlow<P2pSession?>(null)
    val error = MutableStateFlow<String?>(null)
    val transport = MutableStateFlow<String?>(null)
    private var myName = "Player"
    @Volatile private var serverSocket: ServerSocket? = null
    private var bleWire: BleServerWire? = null
    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun shutdown() {
        scope.cancel()
        runCatching { serverSocket?.close() }
        runCatching { bleWire?.close() }
    }

    override fun onCleared() {
        shutdown()
        super.onCleared()
    }

    fun start(name: String, settings: MatchSettings, me: P2pSession.PlayerLocal, bleEnabled: Boolean = true) {
        myName = name
        val token = randomHex(4)
        scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(0)
                serverSocket = server
                val ip = if (isOnWifi(app)) lanIp() else null
                if (ip == null) {

                    runCatching { server.close() }; serverSocket = null
                    if (bleEnabled) startBle(token, settings, me, onlyRadio = true)
                    else error.value = "No Wi-Fi and Bluetooth hosting is off, enable Bluetooth to host."
                    withContext(Dispatchers.Main) {
                        qrPayload.value = "triad://v1?tr=ble&name=${Uri.encode(name)}&t=$token"
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    qrPayload.value = "triad://v1?tr=tcp&ip=$ip&port=${server.localPort}&name=${Uri.encode(name)}&t=$token"
                }
                if (bleEnabled) startBle(token, settings, me, onlyRadio = false)
                val socket = try { server.accept() } catch (_: IOException) { return@launch }
                runCatching { server.close() }; serverSocket = null
                if (!claimed.compareAndSet(false, true)) { socket.close(); return@launch }
                stopBle()
                transport.value = "Wi-Fi"
                val s = newSession(TcpWire(socket, scope), token, settings, me)
                withContext(Dispatchers.Main) { session.value = s }
            } catch (e: Exception) {

                error.value = e.message ?: e::class.java.simpleName
            }
        }
    }

    private fun startBle(token: String, settings: MatchSettings, me: P2pSession.PlayerLocal, onlyRadio: Boolean) {
        if (bleWire != null) return
        val wire = BleServerWire(app, scope)
        bleWire = wire
        val s = newSession(wire, token, settings, me)
        wire.onPeerConnected = {
            if (claimed.compareAndSet(false, true)) {
                runCatching { serverSocket?.close() }; serverSocket = null
                transport.value = "Bluetooth"
                session.value = s
            } else wire.close()
        }
        wire.start(onFail = { raw ->
            bleWire = null
            val e = if (raw.contains("BLUETOOTH_CONNECT") || raw.contains("BLUETOOTH_ADVERTISE") ||
                         raw.contains("android.permission"))
                "Bluetooth permission missing, restart hosting to grant it."
            else raw
            if (onlyRadio && !claimed.get()) error.value = e

        })    }

    private fun stopBle() {
        bleWire?.close(); bleWire = null
    }

    private fun newSession(wire: Wire, token: String, settings: MatchSettings, me: P2pSession.PlayerLocal) =
        P2pSession(wire, isHost = true, myName = myName, token = token, catalog = catalog,
            me = me, hostSettings = settings).also { it.start() }
}

fun needsBleFallback(invite: QrInvite): Boolean =
    invite.transport == "ble" || !sameIpv4Subnet(lanIp(), invite.ip)

suspend fun joinFromInvite(
    context: android.content.Context,
    invite: QrInvite,
    myName: String,
    catalog: Map<String, CardDef>,
    me: P2pSession.PlayerLocal,
    scope: CoroutineScope,
    onStatus: (String) -> Unit = {},
    onTransport: (String) -> Unit = {},
): P2pSession {
    if (needsBleFallback(invite)) {
        onStatus(
            if (invite.transport == "ble") "Connecting over Bluetooth…"
            else if (lanIp() == null) "No Wi-Fi, connecting over Bluetooth…"
            else "Host is on another network, connecting over Bluetooth…")
        val wire = BleJoin.scanAndConnect(context, scope)
        onTransport("Bluetooth")
        return P2pSession(wire, isHost = false, myName = myName, token = invite.token,
            catalog = catalog, me = me, hostSettings = null).also { it.start() }
    }
    onStatus("Connecting over Wi-Fi…")
    var wire: Wire
    var transport = "Wi-Fi"
    try {
        wire = TcpWire(withContext(Dispatchers.IO) {
            Socket().apply {

                connect(java.net.InetSocketAddress(invite.ip, requireNotNull(invite.port) { "invite missing port" }), 3_000)
            }
        }, scope)
    } catch (e: Exception) {
        onStatus("Wi-Fi unreachable, trying Bluetooth…")
        wire = BleJoin.scanAndConnect(context, scope)
        transport = "Bluetooth"
    }
    onTransport(transport)
    return P2pSession(wire, isHost = false, myName = myName, token = invite.token,
        catalog = catalog, me = me, hostSettings = null).also { it.start() }
}
