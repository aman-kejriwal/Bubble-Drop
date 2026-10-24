package dev.bubbledrop

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

const val CHANNEL_SERVICE = "service"
const val CHANNEL_INCOMING = "incoming"

/** App-wide singletons, created once in [BubbleDropApplication]. */
@SuppressLint("StaticFieldLeak")
object Graph {
    lateinit var device: DeviceInfo
        private set
    lateinit var transfers: TransferRepository
        private set
    lateinit var identity: TlsIdentity
        private set
    lateinit var trust: TrustStore
        private set
    lateinit var prompts: Prompts
        private set
    lateinit var server: ReceiveServer
        private set
    lateinit var discovery: Discovery
        private set
    lateinit var sender: Sender
        private set
    lateinit var pairSuggestions: PairSuggestions
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        device = DeviceInfo(id = deviceId(context), name = deviceName(context),
            model = "${Build.MANUFACTURER} ${Build.MODEL}")
        transfers = TransferRepository()
        identity = TlsIdentity.loadOrCreate()
        trust = TrustStore(context)
        prompts = Prompts(context)
        server = ReceiveServer(context, device, identity, trust, prompts, transfers)
        discovery = Discovery(context, device, identity.fingerprint)
        sender = Sender(context, device, identity, trust, prompts, transfers)
        pairSuggestions = PairSuggestions(context, scope, discovery, trust, sender)
    }

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("bubbledrop", Context.MODE_PRIVATE)
        return prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    private fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
}

class BubbleDropApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Ready to receive", NotificationManager.IMPORTANCE_MIN)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Incoming files", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
