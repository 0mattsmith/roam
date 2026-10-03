package app.roam.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which of a phone's several addresses to print.
 *
 * Worth testing precisely because every one of these is "up" and most are
 * site-local, so the flags alone do not separate them -- and printing the
 * wrong one gives someone an address that will never load, with nothing on
 * screen to say why.
 */
class WebAddressTest {

    private fun nic(
        name: String,
        address: String,
        up: Boolean = true,
        loopback: Boolean = false,
        siteLocal: Boolean = true,
    ) = Candidate(name, address, up, loopback, siteLocal)

    @Test
    fun `wifi beats a vpn tunnel that is also up`() {
        val picked = WebAddress.pick(
            listOf(
                nic("tun0", "10.8.0.6"),
                nic("wlan0", "192.168.1.42"),
            )
        )
        assertEquals("192.168.1.42", picked)
    }

    @Test
    fun `wifi beats mobile data`() {
        val picked = WebAddress.pick(
            listOf(
                nic("rmnet_data0", "10.52.14.9"),
                nic("wlan0", "192.168.1.42"),
            )
        )
        assertEquals("192.168.1.42", picked)
    }

    @Test
    fun `loopback is never offered`() {
        assertNull(WebAddress.pick(listOf(nic("lo", "127.0.0.1", loopback = true))))
    }

    @Test
    fun `an interface that is down is not offered`() {
        assertNull(WebAddress.pick(listOf(nic("wlan0", "192.168.1.42", up = false))))
    }

    /**
     * A public address would mean Roam telling someone to open a port to the
     * internet, which is the one thing this feature is not.
     */
    @Test
    fun `a routable address is not offered`() {
        assertNull(WebAddress.pick(listOf(nic("eth0", "93.184.216.34", siteLocal = false))))
    }

    /** Tethering and USB ethernet adapters, where the name is anybody's guess. */
    @Test
    fun `any site-local address beats none at all`() {
        assertEquals(
            "192.168.44.1",
            WebAddress.pick(listOf(nic("swlan0", "192.168.44.1")))
        )
    }

    @Test
    fun `nothing at all is a real answer`() {
        assertNull(WebAddress.pick(emptyList()))
    }

    @Test
    fun `the url carries the port Settings prints`() {
        assertEquals("http://192.168.1.42:8080", WebAddress.url("192.168.1.42"))
        assertEquals(8080, WebAddress.PORT)
    }
}
