package app.roam.core.common

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * One interface's address, flattened to the facts the choice depends on.
 *
 * Exists so the choosing can be tested. `NetworkInterface` cannot be built in
 * a unit test, and the part worth testing is not the enumeration -- it is
 * which of four plausible addresses a phone should advertise.
 */
data class Candidate(
    val name: String,
    val address: String,
    val up: Boolean,
    val loopback: Boolean,
    val siteLocal: Boolean,
)

/**
 * Where the web interface can be reached, and on what port.
 *
 * In `:core:common` rather than in `:feature:webui` because two modules have
 * to agree on it and they are not allowed to depend on each other: the server
 * binds the port, and Settings prints the address next to the PIN. Which port
 * it got travels through the settings store (`webPort`), because a Settings
 * page that said 8080 while the server listened elsewhere would be a bug
 * nothing could catch.
 */
object WebAddress {

    /**
     * The port asked for first. 8080 is conventional and unprivileged -- and
     * popular for exactly that reason: media servers and file-transfer apps
     * take it too, and Roam cannot make another app let go of it.
     */
    const val PORT = 8080

    /**
     * Where the server may land, in order.
     *
     * A short fixed list rather than whatever port the system hands out, so
     * the address stays the same from one start to the next for as long as
     * whatever holds 8080 keeps holding it, and a bookmark keeps working.
     */
    val PORTS: IntRange = PORT..PORT + 9

    fun url(host: String, port: Int = PORT): String = "http://$host:$port"

    /**
     * Wifi first by NAME, which is the part that looks like a hack and is not.
     *
     * A phone routinely has several addresses: wlan0 on the home network, a
     * tunnel if a VPN is up, rmnet for mobile data, plus loopback. A VPN's tun0
     * and mobile data's rmnet are both up and both site-local, so no flag
     * separates them from wifi -- and the laptop this is for is on the wifi.
     * Printing the wrong one gives someone an address that will never load,
     * with nothing on screen to suggest why.
     *
     * Falling back to any site-local address keeps tethering and a USB
     * ethernet adapter working, where the interface name is anybody's guess.
     */
    fun pick(candidates: List<Candidate>): String? {
        val usable = candidates.filter { it.up && !it.loopback && it.siteLocal }
        return usable.firstOrNull { c -> WIFI.any { c.name.startsWith(it) } }?.address
            ?: usable.firstOrNull()?.address
    }

    private val WIFI = listOf("wlan", "ap", "eth")

    /** The real thing. Needs no permission -- enumerating interfaces never has. */
    fun current(): String? = pick(candidates())

    private fun candidates(): List<Candidate> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { nic ->
                val up = runCatching { nic.isUp }.getOrDefault(false)
                val loop = runCatching { nic.isLoopback }.getOrDefault(true)
                nic.inetAddresses.toList()
                    // IPv4 only, deliberately: a link-local IPv6 address needs
                    // a zone index to be typed into a browser at all.
                    .filterIsInstance<Inet4Address>()
                    .map { addr ->
                        Candidate(
                            name = nic.name,
                            address = addr.hostAddress.orEmpty(),
                            up = up,
                            loopback = loop,
                            siteLocal = addr.isSiteLocalAddress,
                        )
                    }
            }
        }.getOrDefault(emptyList()).filter { it.address.isNotBlank() }
}
