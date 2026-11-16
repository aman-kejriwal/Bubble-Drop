package dev.bubbledrop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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

    // Android 14 and later keep watching each service found, by name, so a device that restarts
    // on a new port (or moves to a new address) is followed there. A one-off resolve isn't
    // repeated, and would leave the old port in the list.
    private val executor = context.mainExecutor
    private val watches = HashMap<String, NsdManager.ServiceInfoCallback>()

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
            override fun onServiceFound(info: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) watch(info) else enqueueResolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) unwatch(info.serviceName)
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
        if (Build.VERSION.SDK_INT >= 34) {
            synchronized(watches) { watches.keys.toList() }.forEach(::unwatch)
        }
        _peers.value = emptyList()
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
    }

    /** Keeps the peer for [info] up to date until the service is lost. */
    @RequiresApi(34)
    private fun watch(info: NsdServiceInfo) {
        val name = info.serviceName
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "watch $name failed $errorCode")
                synchronized(watches) { if (watches[name] === this) watches.remove(name) }
            }
            override fun onServiceUpdated(updated: NsdServiceInfo) = add(updated)
            override fun onServiceLost() {
                _peers.update { list -> list.filterNot { it.serviceName == name } }
            }
            override fun onServiceInfoCallbackUnregistered() {}
        }
        synchronized(watches) {
            if (watches.containsKey(name)) return
            watches[name] = callback
        }
        runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }.onFailure {
            Log.w(TAG, "watch $name failed", it)
            synchronized(watches) { watches.remove(name) }
        }
    }

    @RequiresApi(34)
    private fun unwatch(name: String) {
        val callback = synchronized(watches) { watches.remove(name) } ?: return
        runCatching { nsd.unregisterServiceInfoCallback(callback) }
    }

    /** Puts a resolved service in the list, in place of what was known about that device. */
    private fun add(info: NsdServiceInfo) {
        toPeer(info)?.let { peer ->
            _peers.update { list ->
                (list.filterNot { it.id == peer.id || it.serviceName == peer.serviceName } + peer)
                    .sortedBy { it.name.lowercase() }
            }
        }
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
                add(info)
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
