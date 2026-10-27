package app.brisa.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlocklistTest {
    @Before
    fun load() {
        Blocklist.load(listOf("doubleclick.net", "Ads.Example.org"))
    }

    @Test
    fun matchesListedDomainsAndTheirSubdomains() {
        assertTrue(Blocklist.isBlocked("doubleclick.net"))
        assertTrue(Blocklist.isBlocked("stats.g.doubleclick.net"))
        assertTrue(Blocklist.isBlocked("x.ads.example.org"))
    }

    @Test
    fun ignoresEverythingElse() {
        assertFalse(Blocklist.isBlocked("notdoubleclick.net"))
        assertFalse(Blocklist.isBlocked("example.org"))
        assertFalse(Blocklist.isBlocked("net"))
        assertFalse(Blocklist.isBlocked(null))
    }

    @Test
    fun ignoresCase() {
        assertTrue(Blocklist.isBlocked("STATS.DoubleClick.NET"))
    }
}
