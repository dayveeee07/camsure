package com.camsure.profiler.phase4

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

enum class TransportMode { LAN, USB_NETWORK }

/** Public API inventory. Tethering downstream interfaces are not Android Networks.
 * Operator must select the address belonging to their manually enabled tether.
 * Never infer USB from an OEM name or a familiar subnet.
 */
data class UsbNetworkLink(val interfaceName: String, val address: InetAddress, val prefixLength: Short, val interfaceIndex: Int) {
    override fun toString() = "$interfaceName · ${address.hostAddress}/$prefixLength"
    fun isPresent(): Boolean = try {
        val n = NetworkInterface.getByName(interfaceName)
        n != null && n.index == interfaceIndex && n.isUp && n.interfaceAddresses.any { it.address == address && it.networkPrefixLength == prefixLength }
    } catch (_: Exception) { false }

    fun hasExclusivePeerRoute(peer: InetAddress): Boolean = try {
        isPresent() && acceptsPeer(peer) && NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && it.name != interfaceName }
            .none { n -> n.interfaceAddresses.any { a ->
                usable(a.address) && (a.address == address || UsbNetworkLink(n.name, a.address, a.networkPrefixLength, n.index).acceptsPeer(peer))
            } }
    } catch (_: Exception) { false }

    fun acceptsPeer(peer: InetAddress): Boolean {
        if (!usable(peer) || peer == address || prefixLength.toInt() !in 1..30) return false
        val a = address.address; val b = peer.address
        for (bit in 0 until prefixLength.toInt()) {
            val mask = 1 shl (7 - bit % 8)
            if ((a[bit / 8].toInt() and mask) != (b[bit / 8].toInt() and mask)) return false
        }
        val hostMask = (1L shl (32 - prefixLength.toInt())) - 1
        var value = 0L
        b.forEach { value = (value shl 8) or (it.toInt() and 255).toLong() }
        return (value and hostMask) != 0L && (value and hostMask) != hostMask
    }

    companion object {
        fun usable(a: InetAddress): Boolean = a is Inet4Address && !a.isLoopbackAddress && !a.isAnyLocalAddress && !a.isMulticastAddress &&
            !a.isLinkLocalAddress && (a.address[0].toInt() and 255) in 1..223

        @Suppress("DEPRECATION") // allNetworks is available throughout API 26+; inventory only, never selects the default network.
        fun candidates(context: Context): List<UsbNetworkLink> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val excluded = cm?.allNetworks?.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))
                    cm.getLinkProperties(network)?.interfaceName else null
            }?.toSet().orEmpty()
            return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint && it.name !in excluded }
                .flatMap { n -> n.interfaceAddresses.filter { usable(it.address) }.map { UsbNetworkLink(n.name, it.address, it.networkPrefixLength, n.index) } }
        }
    }
}
