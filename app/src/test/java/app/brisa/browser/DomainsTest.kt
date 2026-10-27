package app.brisa.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainsTest {
    private val set = setOf("example.com", "shop.test")

    @Test
    fun matchesTheDomainAndItsSubdomains() {
        assertTrue(Domains.contains(set, "example.com"))
        assertTrue(Domains.contains(set, "www.example.com"))
        assertTrue(Domains.contains(set, "a.b.shop.test"))
    }

    @Test
    fun doesNotMatchLookalikesOrParents() {
        assertFalse(Domains.contains(set, "badexample.com"))
        assertFalse(Domains.contains(set, "com"))
        assertFalse(Domains.contains(set, "test"))
        assertFalse(Domains.contains(set, null))
    }
}
