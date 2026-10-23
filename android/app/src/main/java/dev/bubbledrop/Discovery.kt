package dev.bubbledrop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.InetAddress
import java.util.ArrayDeque

data class Peer(
    val id: String,
    val name: String,
    val model: String,
    val platform: String,
    /** Advertised certificate fingerprint; a UI hint only, trust is decided during TLS. */
    val fingerprint: String?,
    val serviceName: String,
    val address: InetAddress,
    val port: Int,
) {
    val baseUrl: String
        get() {
            val host = address.hostAddress ?: ""
            val literal = if (address is Inet4Address) host else "[${host.replace("%", "%25")}]"
            return "https://$literal:$port"
        }
}

/** Advertises this device and browses for others over DNS-SD (`_bubbledrop._tcp`). */
class Discovery(context: Context, private val device: DeviceInfo, private val fingerprint: String) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    // Older NsdManager versions resolve one service at a time, so queue them.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = device.name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", device.id)
            setAttribute("name", device.name)
            setAttribute("model", device.model)
            setAttribute("platform", device.platform)
            setAttribute("v", PROTOCOL_VERSION.toString())
            setAttribute("fp", fingerprint)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "registered ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "register failed $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun startBrowsing() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) { Log.w(TAG, "browse failed $code") }
            override fun onStopDiscoveryFailed(serviceType: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                _peers.update { list -> list.filterNot { it.serviceName == info.serviceName } }
            }
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        _peers.value = emptyList()
        synchronized(resolveQueue) { resolveQueue.clear() }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            resolveQueue.poll().also { if (it == null) resolving = false }
        } ?: return
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "resolve ${info.serviceName} failed $code")
                resolveNext()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                toPeer(info)?.let { peer ->
                    _peers.update { list ->
                        (list.filterNot { it.id == peer.id || it.serviceName == peer.serviceName } + peer)
                            .sortedBy { it.name.lowercase() }
                    }
                }
                resolveNext()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun toPeer(info: NsdServiceInfo): Peer? {
        val txt = info.attributes.mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } ?: "" }
        val id = txt["id"] ?: info.serviceName
        if (id == device.id) return null
        val address = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
        } else {
            info.host
        } ?: return null
        return Peer(
            id = id,
            name = txt["name"] ?: info.serviceName,
            model = txt["model"] ?: "",
            platform = txt["platform"] ?: "",
            fingerprint = txt["fp"],
            serviceName = info.serviceName,
            address = address,
            port = info.port,
        )
    }

    companion object {
        private const val TAG = "BubbleDropDiscovery"
    }
}
