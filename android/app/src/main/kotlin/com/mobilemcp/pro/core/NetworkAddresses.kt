package com.mobilemcp.pro.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Discovers the addresses a client can actually reach this device on.
 *
 * The previous implementation asked `WifiManager.getConnectionInfo().getIpAddress()`, which
 * has three separate problems: the API is deprecated as of API 31; it returns a packed
 * 32-bit integer and therefore cannot express IPv6 at all; and it only ever describes the
 * Wi-Fi station interface, so it reports "Not connected to WiFi" on a device reachable over
 * Ethernet, USB tethering, or its own hotspot. Users on any of those saw no address and
 * concluded the app was broken.
 *
 * `ConnectivityManager` is asked first, since it reports the address of whichever network
 * the system considers active. Enumerating interfaces directly is the fallback, because it
 * is the only way to see a hotspot interface — the device is not *using* that network, so
 * it is never the active one, but it is exactly where clients connect from.
 */
object NetworkAddresses {

    data class Address(
        val ip: String,
        val interfaceName: String,
        val isLikelyPrimary: Boolean,
    )

    /** Reachable IPv4 addresses, most likely candidate first. */
    fun findLocalAddresses(context: Context): List<Address> {
        val primary = activeNetworkAddress(context)
        val enumerated = enumerateInterfaces()

        val combined = LinkedHashMap<String, Address>()
        primary?.let { combined[it.ip] = it }
        enumerated.forEach { combined.putIfAbsent(it.ip, it) }

        return combined.values.sortedByDescending { it.isLikelyPrimary }
    }

    private fun activeNetworkAddress(context: Context): Address? = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val properties = network?.let { connectivity.getLinkProperties(it) }

        properties?.linkAddresses
            ?.asSequence()
            ?.mapNotNull { it.usableIpv4() }
            ?.firstOrNull()
            ?.let { Address(it, properties.interfaceName ?: "active", isLikelyPrimary = true) }
    } catch (e: SecurityException) {
        Log.w(TAG, "Not permitted to read the active network")
        null
    }

    private fun enumerateInterfaces(): List<Address> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { networkInterface ->
                networkInterface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .mapNotNull { address ->
                        address.hostAddress?.let {
                            Address(it, networkInterface.name, isLikelyPrimary = false)
                        }
                    }
            }
            ?.toList()
            .orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "Could not enumerate network interfaces: ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun LinkAddress.usableIpv4(): String? =
        (address as? Inet4Address)
            ?.takeUnless { it.isLoopbackAddress || it.isLinkLocalAddress }
            ?.hostAddress

    private const val TAG = "NetworkAddresses"
}
