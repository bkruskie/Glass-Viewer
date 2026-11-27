package app.universalmedia.media.mirror

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import app.universalmedia.mirror.protocol.MirrorProtocol
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Finds Universal Media TV receiver apps on the Wi-Fi (DNS-SD / Bonjour). */
class ReceiverDiscovery(context: Context) {

    private val nsd: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, MirrorTarget>()
    private val _receivers = MutableStateFlow<List<MirrorTarget>>(emptyList())
    val receivers: StateFlow<List<MirrorTarget>> = _receivers.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    // NsdManager resolves one service at a time.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null || nsd == null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    resolveQueue.add(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    found.remove(info.serviceName)
                    publish()
                }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(MirrorProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    @Suppress("DEPRECATION") // resolveService is the API available on every supported Android version
    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.poll() ?: return
        val manager = nsd ?: return
        resolving = true
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    val host = serviceInfo.host?.hostAddress
                    if (host != null) {
                        found[serviceInfo.serviceName] = MirrorTarget(
                            id = "receiver:" + serviceInfo.serviceName,
                            name = serviceInfo.serviceName,
                            kind = MirrorTarget.Kind.RECEIVER_APP,
                            host = host,
                            port = serviceInfo.port,
                        )
                        publish()
                    }
                    resolving = false
                    resolveNext()
                }
            }
        })
    }

    private fun publish() {
        _receivers.value = found.values.toList()
    }
}
