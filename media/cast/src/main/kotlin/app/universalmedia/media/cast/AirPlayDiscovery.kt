package app.universalmedia.media.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** Notices TVs that announce Apple AirPlay, so they can be listed with advice (the app can't AirPlay). */
internal class AirPlayDiscovery(context: Context) {
    private val nsd: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null
    @Volatile var names: Set<String> = emptySet()
        private set

    fun start(onChange: () -> Unit) {
        val manager = nsd ?: return
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                // Apple devices (Macs, iPhones, HomePods) also announce AirPlay; keep names that look like TVs.
                val name = info.serviceName.substringAfter('@')
                if (TvClassifier.brandOf(name) != null || Regex("\\btv\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
                    names = names + name
                    onChange()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                names = names - info.serviceName.substringAfter('@')
                onChange()
            }
        }
        listener = l
        runCatching { manager.discoverServices("_airplay._tcp.", NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }
}
