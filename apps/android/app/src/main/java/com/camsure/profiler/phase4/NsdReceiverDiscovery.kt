package com.camsure.profiler.phase4

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor

data class DiscoveredReceiver(
    val serviceName: String,
    val address: Inet4Address,
    val port: Int
) {
    val endpoint: FixedReceiverEndpoint
        get() = FixedReceiverEndpoint(address, port)

    val displayName: String
        get() = "$serviceName · ${address.hostAddress}:$port"
}

/** Foreground DNS-SD browse/resolve lifecycle for CamSure's Windows RTP receiver. */
class NsdReceiverDiscovery(
    context: Context,
    private val onUpdate: (List<DiscoveredReceiver>, String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
        ?: throw IllegalStateException("Android network service discovery is unavailable.")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private val resolved = linkedMapOf<String, DiscoveredReceiver>()
    private val foundServices = linkedMapOf<String, NsdServiceInfo>()
    private val pendingResolutions = mutableSetOf<String>()
    private val serviceInfoCallbacks = linkedMapOf<String, NsdManager.ServiceInfoCallback>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        acquireMulticastLockWhenRequired()
        publish("Searching the local network for CamSure receivers…")

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (isRunning) publish("Searching the local network for CamSure receivers…")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                mainHandler.post {
                    if (!isRunning || !isCamSureService(serviceInfo)) return@post
                    val name = serviceInfo.serviceName
                    foundServices[name] = serviceInfo
                    if (name !in resolved && name !in pendingResolutions && name !in serviceInfoCallbacks) {
                        resolveServiceInfo(serviceInfo)
                    }
                    publish("Found ${foundServices.size} CamSure receiver service${if (foundServices.size == 1) "" else "s"}; resolving endpoints…")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                mainHandler.post { removeService(serviceInfo.serviceName) }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (!isRunning) publish("Receiver discovery stopped.")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                mainHandler.post {
                    if (!isRunning) return@post
                    isRunning = false
                    discoveryListener = null
                    foundServices.clear()
                    pendingResolutions.clear()
                    resolved.clear()
                    releaseMulticastLock()
                    publish("Receiver discovery failed (NSD error $errorCode). Check that the phone and PC share a LAN and that multicast is allowed.")
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (isRunning) publish("Could not stop receiver discovery (NSD error $errorCode).")
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            isRunning = false
            discoveryListener = null
            releaseMulticastLock()
            publish("Receiver discovery failed (${error.javaClass.simpleName}). Check local-network and multicast access.")
        }
    }

    override fun close() {
        if (!isRunning && discoveryListener == null && serviceInfoCallbacks.isEmpty()) {
            releaseMulticastLock()
            return
        }
        isRunning = false
        discoveryListener?.let { listener ->
            try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) { }
        }
        discoveryListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfoCallbacks.values.toList().forEach { callback ->
                unregisterServiceInfoCallback(callback)
            }
        }
        serviceInfoCallbacks.clear()
        foundServices.clear()
        pendingResolutions.clear()
        resolved.clear()
        releaseMulticastLock()
        publish("Receiver discovery stopped.")
    }

    private fun resolveServiceInfo(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            watchServiceInfo(serviceInfo)
        } else {
            resolveOnce(serviceInfo)
        }
    }

    @SuppressLint("NewApi")
    private fun watchServiceInfo(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                mainHandler.post {
                    if (isRunning && name in foundServices) addResolvedService(updatedInfo)
                }
            }

            override fun onServiceLost() {
                mainHandler.post { removeService(name) }
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                mainHandler.post {
                    serviceInfoCallbacks.remove(name)
                    if (isRunning && name in foundServices) resolveOnce(serviceInfo)
                }
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        serviceInfoCallbacks[name] = callback
        try {
            nsdManager.registerServiceInfoCallback(serviceInfo, mainExecutor, callback)
        } catch (_: Exception) {
            serviceInfoCallbacks.remove(name)
            resolveOnce(serviceInfo)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveOnce(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName
        if (!pendingResolutions.add(name)) return
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    mainHandler.post {
                        pendingResolutions.remove(name)
                        if (isRunning && name in foundServices) addResolvedService(resolvedInfo)
                    }
                }

                override fun onResolveFailed(failedInfo: NsdServiceInfo, errorCode: Int) {
                    mainHandler.post {
                        pendingResolutions.remove(name)
                        if (isRunning) publish("Could not resolve ${failedInfo.serviceName} (NSD error $errorCode).")
                    }
                }
            })
        } catch (error: Exception) {
            pendingResolutions.remove(name)
            publish("Could not resolve $name (${error.javaClass.simpleName}).")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    private fun addResolvedService(serviceInfo: NsdServiceInfo) {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses
        } else {
            listOfNotNull(serviceInfo.host)
        }
        val address = addresses.filterIsInstance<Inet4Address>().firstOrNull()
        val port = serviceInfo.port
        if (address == null || port !in 1..65535) {
            publish("${serviceInfo.serviceName} resolved without a usable IPv4 media endpoint.")
            return
        }
        resolved[serviceInfo.serviceName] = DiscoveredReceiver(serviceInfo.serviceName, address, port)
        publish("Resolved ${serviceInfo.serviceName} to ${address.hostAddress}:$port.")
    }

    private fun removeService(name: String) {
        foundServices.remove(name)
        pendingResolutions.remove(name)
        resolved.remove(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfoCallbacks.remove(name)?.let(::unregisterServiceInfoCallback)
        } else {
            serviceInfoCallbacks.remove(name)
        }
        val message = if (foundServices.isEmpty()) {
            "No CamSure receiver is currently visible; discovery remains active for reconnects."
        } else {
            "Receiver $name disappeared; still searching for available receivers."
        }
        publish(message)
    }

    @SuppressLint("NewApi")
    private fun unregisterServiceInfoCallback(callback: NsdManager.ServiceInfoCallback) {
        try { nsdManager.unregisterServiceInfoCallback(callback) } catch (_: Exception) { }
    }

    private fun isCamSureService(serviceInfo: NsdServiceInfo): Boolean =
        serviceInfo.serviceType.trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)

    private fun publish(message: String) {
        val snapshot = resolved.values.sortedBy { it.serviceName }
        if (Looper.myLooper() == Looper.getMainLooper()) onUpdate(snapshot, message)
        else mainHandler.post { if (isRunning || message == "Receiver discovery stopped.") onUpdate(snapshot, message) }
    }

    @SuppressLint("WifiManagerLeak")
    private fun acquireMulticastLockWhenRequired() {
        val foregroundAutoManaged = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
        if (foregroundAutoManaged) return
        try {
            multicastLock = appContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("camsure-nsd-discovery")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: Exception) {
            publish("Android could not enable Wi-Fi multicast reception; DNS-SD discovery may not find receivers.")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) { }
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_camsure-rtp._udp."
    }
}
