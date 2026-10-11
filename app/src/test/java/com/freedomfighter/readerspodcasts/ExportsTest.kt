package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Exports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The export files are named after the app, as every Reader's app names its exports. */
class ExportsTest {
    @Test
    fun `the two files carry the app's name`() {
        assertEquals("readers-podcasts-subscriptions.opml", Exports.OPML_NAME)
        assertEquals("readers-podcasts-settings.json", Exports.SETTINGS_NAME)
        listOf(Exports.OPML_NAME, Exports.SETTINGS_NAME).forEach {
            assertTrue(it, it.startsWith("readers-podcasts-") && it == it.lowercase() && ' ' !in it)
        }
    }

    @Test
    fun `each file is offered with its own type`() {
        assertEquals("text/xml", Exports.OPML_MIME)
        assertEquals("application/json", Exports.SETTINGS_MIME)
    }
}
