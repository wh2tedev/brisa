package app.brisa.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {
    private val search: (String) -> String = { "search:$it" }

    @Test
    fun keepsExplicitWebAddresses() {
        assertEquals("https://example.com/a", Urls.resolve("https://example.com/a", search))
        assertEquals("http://example.com", Urls.resolve("  http://example.com ", search))
    }

    @Test
    fun localAddressesUseHttp() {
        assertEquals("http://localhost:3000", Urls.resolve("localhost:3000", search))
        assertEquals("http://192.168.1.10:8080/admin", Urls.resolve("192.168.1.10:8080/admin", search))
        assertEquals("http://printer.local", Urls.resolve("printer.local", search))
        assertEquals("http://[::1]:5173", Urls.resolve("[::1]:5173", search))
        assertEquals("http://172.20.0.1", Urls.resolve("172.20.0.1", search))
    }

    @Test
    fun publicAddressesUseHttps() {
        assertEquals("https://example.com", Urls.resolve("example.com", search))
        assertEquals("https://example.com/a?b=1", Urls.resolve("example.com/a?b=1", search))
        assertEquals("https://8.8.8.8", Urls.resolve("8.8.8.8", search))
        assertEquals("https://172.32.0.1", Urls.resolve("172.32.0.1", search))
    }

    @Test
    fun everythingElseIsASearch() {
        assertEquals("search:hello world", Urls.resolve("hello world", search))
        assertEquals("search:hello", Urls.resolve("hello", search))
        assertEquals("search:file:///sdcard/a.html", Urls.resolve("file:///sdcard/a.html", search))
        assertEquals("search:javascript:alert(1)", Urls.resolve("javascript:alert(1)", search))
    }

    @Test
    fun recognisesPrivateHosts() {
        assertTrue(Urls.isPrivateHost("localhost"))
        assertTrue(Urls.isPrivateHost("myserver"))
        assertTrue(Urls.isPrivateHost("127.0.0.1"))
        assertTrue(Urls.isPrivateHost("10.1.2.3"))
        assertTrue(Urls.isPrivateHost("192.168.0.1"))
        assertTrue(Urls.isPrivateHost("169.254.1.1"))
        assertFalse(Urls.isPrivateHost("example.com"))
        assertFalse(Urls.isPrivateHost("110.1.1.1"))
        assertFalse(Urls.isPrivateHost(null))
    }
}
