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

data class QrInvite(val transport: String, val ip: String?, val port: Int?, val name: String,
                    val token: String, val mode: String = "match")

fun parseInvite(raw: String): QrInvite? = runCatching {
    val uri = Uri.parse(raw)
    check(uri.scheme == "triad" && uri.host == "v1")
    QrInvite(
        transport = uri.getQueryParameter("tr") ?: "tcp",
        ip = uri.getQueryParameter("ip"),
        port = uri.getQueryParameter("port")?.toInt(),
        name = uri.getQueryParameter("name") ?: "?",
        token = uri.getQueryParameter("t") ?: "",
        mode = uri.getQueryParameter("mode") ?: "match")
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

    fun start(settings: MatchSettings, me: P2pSession.PlayerLocal, bleEnabled: Boolean = true) {
        myName = "Host"
        val token = secureHex(4)
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
                        qrPayload.value = "triad://v1?tr=ble&name=Host&t=$token"
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    qrPayload.value = "triad://v1?tr=tcp&ip=$ip&port=${server.localPort}&name=Host&t=$token"
                }
                if (bleEnabled) startBle(token, settings, me, onlyRadio = false)
                // A2: the first socket to arrive is not necessarily a friend — a LAN
                // port-scanner wins accept() and then fails the token check, which
                // used to wedge hosting. Keep accepting until a peer PROVES the
                // token in their Hello.
                while (!claimed.get()) {
                    val socket = try { server.accept() } catch (_: IOException) { return@launch }
                    if (gate(TcpWire(socket, scope), token, settings, me)) {
                        runCatching { server.close() }; serverSocket = null
                        stopBle()
                        transport.value = "Wi-Fi"
                        return@launch
                    }
                }
                runCatching { server.close() }; serverSocket = null
            } catch (e: Exception) {

                error.value = e.message ?: e::class.java.simpleName
            }
        }
    }

    /** Run one candidate session; true iff the peer's Hello carried the token and we
     *  claimed it. The gate's collector consumes exactly one event (Connected) and
     *  hands the stream to the UI — events are exclusive-consumer. */
    private suspend fun gate(wire: Wire, token: String, settings: MatchSettings,
                             me: P2pSession.PlayerLocal): Boolean =
        withTimeoutOrNull(6_000) {
            val s = newSession(wire, token, settings, me)
            try {
                suspendCancellableCoroutine { cont ->
                    var decided = false
                    var job: Job? = null
                    fun finish(v: Boolean) {
                        if (!decided) { decided = true; cont.resumeWith(Result.success(v)) }
                    }
                    job = scope.launch {
                        s.eventsFlow.collect { e ->
                            when (e) {
                                is SessionEvent.Connected -> {
                                    job?.cancel()
                                    if (claimed.compareAndSet(false, true)) {
                                        session.value = s; finish(true)
                                    } else finish(false)
                                }
                                is SessionEvent.Declined, is SessionEvent.Disconnected,
                                is SessionEvent.Forfeit, is SessionEvent.ProtocolError -> finish(false)
                                else -> {}
                            }
                        }
                    }
                    cont.invokeOnCancellation { job?.cancel(); runCatching { wire.close() } }
                }
            } finally {
                if (session.value !== s) runCatching { wire.close() }
            }
        } ?: false

    private var bleOnly = false
    private var bleRestarts = 0

    private fun startBle(token: String, settings: MatchSettings, me: P2pSession.PlayerLocal, onlyRadio: Boolean) {
        if (bleWire != null) return
        bleRestarts = 0
        spinBle(token, settings, me, onlyRadio)
    }

    private fun spinBle(token: String, settings: MatchSettings, me: P2pSession.PlayerLocal, onlyRadio: Boolean) {
        bleOnly = onlyRadio
        val wire = BleServerWire(app, scope)
        bleWire = wire
        val s = newSession(wire, token, settings, me)
        wire.onPeerConnected = { transport.value = "Bluetooth" }
        val job = scope.launch {
            s.eventsFlow.collect { e ->
                when (e) {
                    // claim only on token proof, not on first subscriber (A2)
                    is SessionEvent.Connected -> {
                        if (claimed.compareAndSet(false, true)) {
                            runCatching { serverSocket?.close() }; serverSocket = null
                            session.value = s
                        } else wire.close()
                    }
                    is SessionEvent.Declined, is SessionEvent.Disconnected,
                    is SessionEvent.Forfeit, is SessionEvent.ProtocolError ->
                        if (!claimed.get()) restartBle(token, settings, me)
                    else -> {}
                }
            }
        }
        wire.start(onFail = { raw ->
            bleWire = null
            job.cancel()
            val e = if (raw.contains("BLUETOOTH_CONNECT") || raw.contains("BLUETOOTH_ADVERTISE") ||
                         raw.contains("android.permission"))
                "Bluetooth permission missing, restart hosting to grant it."
            else raw
            if (bleOnly && !claimed.get()) error.value = e

        })    }

    /** A bogus first subscriber faulted the BLE session pre-claim: rebuild the GATT
     *  server and keep waiting instead of wedging the host. */
    private fun restartBle(token: String, settings: MatchSettings, me: P2pSession.PlayerLocal) {
        if (claimed.get()) return
        bleWire?.let { runCatching { it.close() } }
        bleWire = null
        if (++bleRestarts > 5) {
            if (bleOnly && !claimed.get()) error.value = "Bluetooth kept failing, try hosting again."
            return
        }
        spinBle(token, settings, me, bleOnly)
    }

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
