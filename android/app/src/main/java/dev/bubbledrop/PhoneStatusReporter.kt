package dev.bubbledrop

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Reports this phone's battery, Bluetooth accessories, connections and sound settings to paired
 * Macs for their desktop widget (PROTOCOL.md "Status"): whenever one changes, and every minute
 * so the Mac can tell when the phone is gone. Runs while BubbleDrop is on.
 */
@SuppressLint("StaticFieldLeak")
object PhoneStatusReporter {
    private const val HEARTBEAT_MS = 60_000L
    private const val DEBOUNCE_MS = 1_500L
    /** Hidden broadcast with a Bluetooth accessory's battery level (sent since Android 8.1). */
    private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    /** BluetoothProfile.HID_HOST (keyboards, mice); the constant isn't public. */
    private const val HID_HOST = 4
    /** Hidden broadcast and extra for the hotspot turning on or off; 13 = on, 11 = off. */
    private const val ACTION_HOTSPOT_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_HOTSPOT_STATE = "wifi_state"
    private const val HOTSPOT_ENABLED = 13
    private const val HOTSPOT_DISABLED = 11

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var lastSent: String? = null
    private var lastSentAt = 0L
    private var peerWatch: Job? = null
    /** Bluetooth profile proxies for listing connected audio and input accessories. */
    private val profiles = mutableMapOf<Int, BluetoothProfile>()
    /** Accessories seen connecting, by address; covers kinds the profiles don't list. */
    private val aclConnected = mutableSetOf<String>()
    private val accessoryBattery = mutableMapOf<String, Int>()
    /** The hotspot state from its broadcast, once one has arrived. */
    private var hotspotFromBroadcast: Boolean? = null

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> device(intent)?.address?.let(aclConnected::add)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> device(intent)?.address?.let {
                    aclConnected.remove(it)
                    accessoryBattery.remove(it)
                }
                ACTION_HOTSPOT_STATE_CHANGED -> when (intent.getIntExtra(EXTRA_HOTSPOT_STATE, -1)) {
                    HOTSPOT_ENABLED -> hotspotFromBroadcast = true
                    HOTSPOT_DISABLED -> hotspotFromBroadcast = false
                }
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    device(intent)?.address?.let { if (level in 0..100) accessoryBattery[it] = level }
                }
            }
            schedule()
        }
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = schedule()
        override fun onLost(network: Network) = schedule()
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            send(force = true)
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val debounced = Runnable { send(force = false) }

    fun start(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(ACTION_HOTSPOT_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(app, changed, filter, ContextCompat.RECEIVER_EXPORTED)
        runCatching { app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
        openProfiles(app)
        main.post(heartbeat)
        // Report straight away when a Mac comes into view, so its widget fills in without waiting.
        peerWatch = Graph.scope.launch {
            Graph.discovery.peers
                .map { peers -> peers.filter { it.platform == "macos" }.mapNotNull { it.fingerprint }.toSet() }
                .distinctUntilChanged()
                .collect { main.post { send(force = true) } }
        }
    }

    /** Bluetooth permission was just granted: start listing accessories. */
    fun bluetoothAllowed() {
        context?.let { main.post { openProfiles(it); schedule() } }
    }

    fun stop() {
        val app = context ?: return
        context = null
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(debounced)
        peerWatch?.cancel()
        runCatching { app.unregisterReceiver(changed) }
        runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        profiles.forEach { (id, proxy) -> runCatching { adapter?.closeProfileProxy(id, proxy) } }
        profiles.clear()
        lastSent = null
    }

    private fun schedule() {
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    /** Sends the status if it changed, or regardless when [force] (the heartbeat). */
    private fun send(force: Boolean) {
        val context = context ?: return
        val status = runCatching { collect(context) }.getOrNull() ?: return
        val json = status.toString()
        val now = System.currentTimeMillis()
        if (!force && json == lastSent && now - lastSentAt < HEARTBEAT_MS) return
        lastSent = json
        lastSentAt = now
        NotificationRelay.forward("/api/v2/status", status)
    }

    private fun collect(context: Context): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale
        } ?: 0
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        val toFull = if (plugged && Build.VERSION.SDK_INT >= 28) {
            context.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
                .takeIf { it > 0 }?.let { (it / 60_000).toInt() }
        } else null

        val audio = context.getSystemService(AudioManager::class.java)
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "ring"
        }
        val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        val dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val bluetoothOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

        return JSONObject()
            .put("battery", level.coerceIn(0, 100))
            .put("charging", plugged)
            .apply { toFull?.let { put("minutesToFull", it) } }
            .put("powerSaver", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
            .put("bluetoothOn", bluetoothOn)
            .put("accessories", if (bluetoothOn) accessories(context) else JSONArray())
            .apply { putNetwork(context, this) }
            .put("hotspot", hotspotOn(context))
            .put("ringer", ringer)
            .put("doNotDisturb", dnd)
    }

    private fun putNetwork(context: Context, out: JSONObject) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        out.put("wifi", onWifi)
        if (onWifi) {
            val wifi = context.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                wifi.calculateSignalLevel(info.rssi) * 4 / wifi.maxSignalLevel.coerceAtLeast(1)
            } else {
                @Suppress("DEPRECATION")
                WifiManager.calculateSignalLevel(info.rssi, 5)
            }
            out.put("wifiBars", bars.coerceIn(0, 4))
            // Android hides the network name unless the app may use location; send it only when known.
            info.ssid?.takeIf { it != WifiManager.UNKNOWN_SSID }?.trim('"')?.takeIf { it.isNotBlank() }
                ?.let { out.put("wifiName", it) }
        }
        val telephony = context.getSystemService(TelephonyManager::class.java)
        if (telephony != null && telephony.simState == TelephonyManager.SIM_STATE_READY) {
            telephony.networkOperatorName?.takeIf { it.isNotBlank() }?.let { out.put("carrier", it) }
            if (Build.VERSION.SDK_INT >= 28) telephony.signalStrength?.level?.let { out.put("cellBars", it.coerceIn(0, 4)) }
        }
    }

    /**
     * Whether the phone's Wi-Fi hotspot is on. Android has no public API for it, so: the state
     * broadcast if one has arrived, else the hidden WifiManager.isWifiApEnabled() where the phone
     * still allows it, else whether a hotspot network interface is up with an address.
     */
    private fun hotspotOn(context: Context): Boolean {
        hotspotFromBroadcast?.let { return it }
        runCatching {
            val wifi = context.getSystemService(WifiManager::class.java)
            return WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { iface ->
                iface.isUp && !iface.isLoopback && HOTSPOT_INTERFACE.matches(iface.name) &&
                    iface.inetAddresses.toList().any { it is Inet4Address }
            }
        }.getOrDefault(false)
    }

    /** Interface names phones use for the hotspot (ap0, swlan0, softap0, wlan1 alongside wlan0…). */
    private val HOTSPOT_INTERFACE = Regex("^(ap|swlan|softap|wigig|wlan1)\\d*$")

    // Bluetooth accessories

    private fun canUseBluetooth(context: Context) = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun openProfiles(context: Context) {
        if (!canUseBluetooth(context)) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                profiles[profile] = proxy
                schedule()
            }
            override fun onServiceDisconnected(profile: Int) {
                profiles.remove(profile)
            }
        }
        val wanted = buildList {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            add(BluetoothProfile.HEARING_AID)
            if (Build.VERSION.SDK_INT >= 33) add(BluetoothProfile.LE_AUDIO)
            add(HID_HOST)
        }
        wanted.forEach { runCatching { adapter.getProfileProxy(context, listener, it) } }
    }

    @SuppressLint("MissingPermission")
    private fun accessories(context: Context): JSONArray {
        val out = JSONArray()
        if (!canUseBluetooth(context)) return out
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return out
        val devices = linkedMapOf<String, BluetoothDevice>()
        profiles.values.forEach { proxy ->
            runCatching { proxy.connectedDevices }.getOrNull()?.forEach { devices[it.address] = it }
        }
        runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull()?.forEach { devices[it.address] = it }
        runCatching { manager.adapter.bondedDevices }.getOrNull()
            ?.filter { it.address in aclConnected }?.forEach { devices[it.address] = it }

        devices.values.forEach { device ->
            val name = runCatching { if (Build.VERSION.SDK_INT >= 30) device.alias else device.name }.getOrNull()
                ?: device.name ?: return@forEach
            val battery = accessoryBattery[device.address] ?: hiddenBatteryLevel(device)
            out.put(JSONObject().put("name", name).put("kind", kind(device))
                .apply { battery?.let { put("battery", it) } })
        }
        return out
    }

    /** BluetoothDevice.getBatteryLevel() isn't public; it answers -1 when the accessory doesn't say. */
    private fun hiddenBatteryLevel(device: BluetoothDevice): Int? = runCatching {
        BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
    }.getOrNull()?.takeIf { it in 0..100 }

    @SuppressLint("MissingPermission")
    private fun kind(device: BluetoothDevice): String {
        val cls = runCatching { device.bluetoothClass }.getOrNull() ?: return "other"
        return when (cls.majorDeviceClass) {
            BluetoothClass.Device.Major.WEARABLE -> "watch"
            BluetoothClass.Device.Major.PERIPHERAL -> "input"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "car"
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "speaker"
                else -> "headphones"
            }
            else -> "other"
        }
    }

    private fun device(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}
