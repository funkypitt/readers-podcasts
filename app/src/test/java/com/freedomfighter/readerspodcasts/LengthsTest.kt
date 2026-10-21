package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Lengths
import com.freedomfighter.readerspodcasts.net.Extractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LengthsTest {
    private fun ep(id: String, video: String, ms: Long = 0L) =
        Episode(id = id, feedId = "f", title = id, published = 0L, mediaUrl = "https://www.youtube.com/watch?v=$video", durationMs = ms)

    @Test fun videoIdFromWatchUrl() {
        assertEquals("aqz-KE-bpKQ", Lengths.videoId("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertEquals("abc_123", Lengths.videoId("https://www.youtube.com/watch?feature=x&v=abc_123&t=3"))
        assertEquals("gDQB1rGT4pg", Lengths.videoId("https://www.youtube.com/shorts/gDQB1rGT4pg"))
        assertEquals("gDQB1rGT4pg", Lengths.videoId("https://youtu.be/gDQB1rGT4pg?t=2"))
        assertNull(Lengths.videoId("https://example.com/a.mp3"))
    }

    @Test fun onlyVideosWithoutALengthAreLacking() {
        val eps = listOf(
            ep("a", "v1"), ep("b", "v2", 600_000L), ep("c", "v3", Lengths.UNKNOWN),
            Episode(id = "s", feedId = "f", title = "s", published = 0L, mediaUrl = "https://www.youtube.com/shorts/v4"),
            Episode(id = "d", feedId = "f", title = "d", published = 0L, mediaUrl = "https://example.com/a.mp3"),
        )
        assertEquals(listOf("a", "s"), Lengths.lacking(eps).map { it.id })
    }

    @Test fun lengthsComeFromTheListingByVideoId() {
        val eps = listOf(ep("a", "v1"), ep("b", "v2", 500L), ep("c", "v3"))
        val listed = listOf(
            Extractor.Listed("v1", "one", 741_000L), Extractor.Listed("v2", "two", 1_000L),
            Extractor.Listed("v3", "three", 0L), Extractor.Listed("v9", "nine", 10L),
        )
        assertEquals(mapOf("a" to 741_000L), Lengths.fromListing(eps, listed))
    }
}
