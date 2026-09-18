package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Backup
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Feed
import com.freedomfighter.readerspodcasts.data.Opml
import com.freedomfighter.readerspodcasts.data.State
import com.freedomfighter.readerspodcasts.data.episodeId
import com.freedomfighter.readerspodcasts.data.feedId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

/**
 * The phone and the desktop, against each other's files.
 *
 * There is no server between the two apps: `abonnements.opml` and `reglages.json` *are* the
 * synchronisation, so what matters is not that each app reads its own writing but that it reads
 * the other's. The fixtures under `resources/` were written by the desktop app itself
 * (tools/make-fixtures.sh), and what this test writes into `build/interop/` is read back by the
 * desktop in the same script. A drift on either side fails here rather than on someone's phone.
 */
class InteropTest {

    private fun parser() = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    @Test fun `the desktop's opml is read, apostrophes and ampersands included`() {
        val lines = Opml.parse(resource("desktop-abonnements.opml").inputStream(), parser())
        assertEquals(2, lines.size)
        assertEquals("https://example.org/grande-table.xml", lines[0].url)
        assertEquals("La Grande Table", lines[0].title)
        // &apos; and &amp; in one title: both sides escape, both sides must unescape.
        assertEquals("Le Cours de l'histoire & suite", lines[1].title)
    }

    @Test fun `the desktop's settings file carries feeds and positions across`() {
        val restored = Backup.parse(resource("desktop-reglages.json").decodeToString())
        assertEquals("LIGHT", restored.settings["theme"])
        assertEquals(false, restored.settings["auto_refresh"])
        assertEquals(2, restored.feeds.size)
        val first = restored.feeds.first()
        assertEquals("https://example.org/grande-table.xml", first.url)
        assertTrue(first.autoDownload)
        assertEquals(20, first.keepCount)

        // The point of the whole exercise: an episode id worked out on the desktop is the id the
        // phone works out for the same episode, so the position lands on the right row.
        val fid = feedId("https://example.org/grande-table.xml")
        assertEquals(fid, first.id)
        val started = restored.states.single { it.state == State.STARTED }
        assertEquals(episodeId(fid, "episode-42"), started.id)
        assertEquals(754_000L, started.positionMs)
        // A favourite travels even untouched, or one kept on the desktop would vanish here.
        val starred = restored.states.single { it.starred }
        assertEquals(episodeId(fid, "episode-44"), starred.id)
        // An episode neither begun nor starred is not in the file at all.
        assertEquals(2, restored.states.size)
    }

    @Test fun `what the phone writes is left where the desktop reads it`() {
        val feeds = listOf(
            Feed(id = feedId("https://example.org/phone.xml"), url = "https://example.org/phone.xml",
                 title = "Une chaîne « à part »", autoDownload = true, keepCount = 30),
        )
        val fid = feeds.first().id
        val episodes = listOf(
            Episode(id = episodeId(fid, "p-1"), feedId = fid, title = "Un épisode commencé",
                    published = 1_758_000_000_000, mediaUrl = "https://example.org/1.mp3",
                    positionMs = 321_000, state = State.STARTED, lastPlayed = 1_758_100_000_000),
            Episode(id = episodeId(fid, "p-2"), feedId = fid, title = "Un épisode neuf",
                    published = 1_758_000_000_000, mediaUrl = "https://example.org/2.mp3"),
            Episode(id = episodeId(fid, "p-3"), feedId = fid, title = "Un favori",
                    published = 1_758_000_000_000, mediaUrl = "https://example.org/3.mp3", starred = true),
        )
        val dir = File("build/interop").apply { mkdirs() }
        File(dir, "phone-abonnements.opml").writeText(Opml.export(feeds))
        File(dir, "phone-reglages.json").writeText(
            Backup.export(mapOf("theme" to "DARK", "speed" to 1.25, "delete_when_played" to false), feeds, episodes)
        )
        // Read back here too, so a broken write fails in this test and not only in the script.
        val again = Opml.parse(File(dir, "phone-abonnements.opml").inputStream(), parser())
        assertEquals("Une chaîne « à part »", again.single().title)
        val back = Backup.parse(File(dir, "phone-reglages.json").readText())
        assertEquals(2, back.states.size)
        assertEquals(episodeId(fid, "p-3"), back.states.single { it.starred }.id)
    }
}
