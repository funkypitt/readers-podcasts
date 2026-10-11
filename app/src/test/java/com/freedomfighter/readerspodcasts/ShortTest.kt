package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.withoutShort
import org.junit.Assert.assertEquals
import org.junit.Test

/** The setting that leaves out the episodes under ten minutes. */
class ShortTest {
    private fun ep(id: String, minutes: Long) = Episode(id = id, feedId = "f", title = id, published = 0, mediaUrl = "m", durationMs = minutes * 60_000)
    private val list = listOf(ep("trailer", 2), ep("talk", 45), ep("unknown", 0), ep("nine", 9), ep("ten", 10))

    @Test fun offShowsEverything() = assertEquals(list, list.withoutShort(false))

    @Test fun onLeavesOutTheShortOnesButKeepsUnknownLengths() =
        assertEquals(listOf("talk", "unknown", "ten"), list.withoutShort(true).map { it.id })

    @Test fun ownPicksAreNeverHidden() = assertEquals(list, list.withoutShort(true, ownPicks = true))
}
