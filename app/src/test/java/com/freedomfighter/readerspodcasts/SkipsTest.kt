package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Chapter
import com.freedomfighter.readerspodcasts.data.Chapters
import com.freedomfighter.readerspodcasts.data.SkipSegment
import com.freedomfighter.readerspodcasts.data.SkipWatch
import com.freedomfighter.readerspodcasts.data.Skips
import com.freedomfighter.readerspodcasts.data.SponsorBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipsTest {

    // ---- SponsorBlock: the address and the answer ----

    @Test fun `only four characters of the video's fingerprint go into the address`() {
        // sha256("dQw4w9WgXcQ") begins 5f6b
        assertEquals("5f6b", SponsorBlock.prefix("dQw4w9WgXcQ"))
        val url = SponsorBlock.url("dQw4w9WgXcQ")
        assertTrue(url.startsWith("https://sponsor.ajay.app/api/skipSegments/5f6b?categories="))
        assertTrue("the id itself must not leave", !url.contains("dQw4w9WgXcQ"))
        assertEquals(
            "%5B%22sponsor%22%2C%22selfpromo%22%2C%22interaction%22%5D",
            url.substringAfter("categories="),
        )
    }

    private val answer = """
        [
          {"videoID":"other00000A","segments":[{"category":"sponsor","actionType":"skip","segment":[1.0,2.0],"UUID":"x"}]},
          {"videoID":"dQw4w9WgXcQ","segments":[
            {"category":"interaction","actionType":"skip","segment":[300.5,310.25],"UUID":"c"},
            {"category":"sponsor","actionType":"skip","segment":[12.0,45.5],"UUID":"a"},
            {"category":"sponsor","actionType":"mute","segment":[100.0,110.0],"UUID":"m"},
            {"category":"selfpromo","actionType":"skip","segment":[200.0,200.0],"UUID":"empty"},
            {"category":"selfpromo","actionType":"skip","segment":[220.0,230.0],"UUID":"b"}
          ]}
        ]
    """.trimIndent()

    @Test fun `the video wanted is picked out, skips only, in order`() {
        val segments = SponsorBlock.parse(answer, "dQw4w9WgXcQ")
        assertEquals(listOf("a", "b", "c"), segments.map { it.uuid })
        assertEquals(SkipSegment(12_000, 45_500, "sponsor", "a"), segments[0])
        assertEquals(310_250, segments[2].endMs)
    }

    @Test fun `a video absent from the answer, or an answer that is not one, has no segment`() {
        assertEquals(emptyList<SkipSegment>(), SponsorBlock.parse(answer, "notThere000"))
        assertEquals(emptyList<SkipSegment>(), SponsorBlock.parse("Not Found", "dQw4w9WgXcQ"))
    }

    @Test fun `segments survive being kept with the episode`() {
        val segments = SponsorBlock.parse(answer, "dQw4w9WgXcQ")
        assertEquals(segments, SponsorBlock.fromJson(org.json.JSONArray(SponsorBlock.toJson(segments).toString())))
    }

    // ---- the channel's start and end ----

    @Test fun `an episode from its beginning starts past the intro, one begun stays where it was`() {
        assertEquals(30_000, Skips.startAt(0, 30_000, 3_600_000))
        assertEquals(30_000, Skips.startAt(400, 30_000, 0))
        assertEquals(12_000, Skips.startAt(12_000, 30_000, 3_600_000))
        assertEquals(600_000, Skips.startAt(600_000, 30_000, 3_600_000))
        assertEquals(0, Skips.startAt(0, 0, 3_600_000))
        // Nothing would be left to hear.
        assertEquals(0, Skips.startAt(0, 30_000, 20_000))
    }

    @Test fun `the end comes that much earlier, when there is something left`() {
        assertEquals(3_540_000L, Skips.endAt(3_600_000, 60_000))
        assertNull(Skips.endAt(3_600_000, 0))
        assertNull(Skips.endAt(0, 60_000))
        assertNull(Skips.endAt(30_000, 60_000))
    }

    // ---- the watch ----

    private val sponsor = SkipSegment(60_000, 90_000, "sponsor", "s")
    private val subscribe = SkipSegment(300_000, 310_000, "interaction", "i")

    private fun watch(start: Long = 0) = SkipWatch().apply { segments = listOf(sponsor, subscribe); begin(start) }

    @Test fun `playback running into a segment jumps to its end`() {
        val w = watch()
        assertEquals(SkipWatch.Action.None, w.tick(59_800, null))
        assertEquals(SkipWatch.Action.Skip(sponsor), w.tick(60_000, null))
        w.seeked(90_000)
        assertEquals(SkipWatch.Action.None, w.tick(90_200, null))
        assertEquals(SkipWatch.Action.Skip(subscribe), w.tick(300_150, null))
    }

    @Test fun `a segment at the very start is skipped when the episode starts from 0`() {
        val w = SkipWatch().apply { segments = listOf(SkipSegment(0, 15_000, "sponsor", "z")); begin(0) }
        assertEquals(SkipWatch.Action.Skip(SkipSegment(0, 15_000, "sponsor", "z")), w.tick(0, null))
    }

    @Test fun `a segment one seeks into by hand plays`() {
        val w = watch()
        w.tick(10_000, null)
        w.seeked(70_000)
        assertEquals(SkipWatch.Action.None, w.tick(70_200, null))
        assertEquals(SkipWatch.Action.None, w.tick(89_900, null))
    }

    @Test fun `resuming inside a segment does not jump`() {
        val w = watch(start = 75_000)
        assertEquals(SkipWatch.Action.None, w.tick(75_200, null))
    }

    @Test fun `a segment gone back to is not skipped again in this listening, but is in the next`() {
        val w = watch()
        w.tick(59_000, null)
        assertEquals(SkipWatch.Action.Skip(sponsor), w.tick(60_100, null))
        w.spare("s")
        w.seeked(60_000)
        assertEquals(SkipWatch.Action.None, w.tick(60_200, null))
        // Back before it, and through it again: still heard.
        w.seeked(30_000)
        assertEquals(SkipWatch.Action.None, w.tick(60_100, null))
        // A new listening forgets.
        w.begin(0)
        w.tick(59_000, null)
        assertEquals(SkipWatch.Action.Skip(sponsor), w.tick(60_100, null))
    }

    @Test fun `running into the end the channel asked for ends the episode, going there by hand does not`() {
        val w = watch()
        w.tick(3_539_900, 3_540_000)
        assertEquals(SkipWatch.Action.End, w.tick(3_540_100, 3_540_000))
        val v = watch()
        v.seeked(3_550_000)
        assertEquals(SkipWatch.Action.None, v.tick(3_550_200, 3_540_000))
    }

    @Test fun `a segment that reaches into the end is the end`() {
        val w = SkipWatch().apply { segments = listOf(SkipSegment(3_500_000, 3_600_000, "selfpromo", "o")); begin(0) }
        w.tick(3_499_900, 3_540_000)
        assertEquals(SkipWatch.Action.End, w.tick(3_500_100, 3_540_000))
    }

    // ---- next chapter ----

    @Test fun `the next chapter is the first that starts after where one is`() {
        val chapters = listOf(Chapter(0, "a"), Chapter(60_000, "b"), Chapter(120_000, "c"))
        assertEquals("b", Chapters.next(chapters, 0)?.title)
        assertEquals("c", Chapters.next(chapters, 60_000)?.title)
        assertNull(Chapters.next(chapters, 130_000))
    }
}
