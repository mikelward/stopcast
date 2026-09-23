package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding the web links in a service alert, with and without a scheme, minus trailing punctuation. */
class AlertLinksTest {
    private fun links(text: String) = AlertLinks.find(text).map { text.substring(it.range) to it.url }

    @Test
    fun `a full URL is linked as written`() {
        assertEquals(
            listOf("https://tfl.gov.uk/strikes" to "https://tfl.gov.uk/strikes"),
            links("Check https://tfl.gov.uk/strikes before you travel."),
        )
    }

    @Test
    fun `a bare domain with a path gets https and drops the full stop`() {
        assertEquals(
            listOf("tfl.gov.uk/status-updates" to "https://tfl.gov.uk/status-updates"),
            links("For more information visit tfl.gov.uk/status-updates."),
        )
    }

    @Test
    fun `a www host and a bracketed link lose their closing punctuation`() {
        assertEquals(
            listOf("www.tfl.gov.uk" to "https://www.tfl.gov.uk"),
            links("(see www.tfl.gov.uk)"),
        )
    }

    @Test
    fun `ordinary prose and abbreviations aren't linked`() {
        assertTrue(links("Severe delays, e.g. between Victoria and Brixton. No step-free access.").isEmpty())
    }

    @Test
    fun `several links are all found`() {
        assertEquals(
            listOf("tfl.gov.uk" to "https://tfl.gov.uk", "http://example.com/a" to "http://example.com/a"),
            links("See tfl.gov.uk or http://example.com/a, thanks"),
        )
    }

    @Test
    fun `an email address isn't linked as a website`() {
        assertTrue(links("Contact help@tfl.gov.uk for assistance.").isEmpty())
    }
}
