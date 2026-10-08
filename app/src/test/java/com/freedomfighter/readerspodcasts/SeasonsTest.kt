package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Feed
import com.freedomfighter.readerspodcasts.data.FeedParser
import com.freedomfighter.readerspodcasts.data.Kind
import com.freedomfighter.readerspodcasts.data.Opml
import com.freedomfighter.readerspodcasts.data.Seasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParserFactory

/** Seasons and serials — the same cases as the desktop's tests/test_seasons.py. */
class SeasonsTest {

    private fun parser() = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()

    private fun item(n: Int, season: Int? = null, number: Int? = null, extra: String = "") =
        "<item><title>Titre $n</title><guid>g$n</guid><pubDate>Mon, 06 Jul 2026 %02d:00:00 +0000</pubDate>".format(n) +
            "<enclosure url=\"https://example.org/e$n.mp3\" type=\"audio/mpeg\" length=\"1\"/>" + extra +
            (season?.let { "<itunes:season>$it</itunes:season>" } ?: "") +
            (number?.let { "<itunes:episode>$it</itunes:episode><itunes:episodeType>full</itunes:episodeType>" } ?: "") +
            "</item>"

    private fun parse(kind: String, vararg items: String) = FeedParser.parse(
        "f", Kind.RSS,
        ("<?xml version=\"1.0\"?><rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\" " +
            "xmlns:podcast=\"https://podcastindex.org/namespace/1.0\"><channel><title>Une série</title>" +
            kind + items.joinToString("") + "</channel></rss>").byteInputStream(),
        parser(),
    )

    private val serial = "<itunes:type>serial</itunes:type>"

    @Test fun theFeedSaysSerialAndTheItemsTheirPlace() {
        val p = parse(serial, item(1, 1, 1), item(2, 1, 2), item(3, 2, 1))
        assertEquals("Une série", p.title)
        assertTrue(p.serial)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), p.episodes.map { it.season to it.number })
    }

    @Test fun aFeedThatSaysNothingIsNotASerial() {
        val p = parse("", item(1))
        assertFalse(p.serial)
        assertEquals(0 to 0, p.episodes[0].season to p.episodes[0].number)
        assertTrue(Seasons.shelves(p.episodes, p.serial).isEmpty())
    }

    @Test fun aSeasonMayCarryAName() {
        val p = parse("", item(1, null, 1, "<podcast:season name=\"Les soirs\">2</podcast:season>"), item(2, 1, 1))
        assertEquals(2 to "Les soirs", p.episodes[0].season to p.episodes[0].seasonName)
        assertEquals(listOf(2 to "Les soirs", 1 to ""), Seasons.shelves(p.episodes, false).map { it.season to it.name })
    }

    @Test fun aSerialIsReadFromItsFirstSeasonDown() {
        val p = parse(serial, item(1, 1, 1), item(2, 1, 2), item(3, 2, 1), item(4, 2, 2), item(5))
        val found = Seasons.shelves(p.episodes.reversed(), p.serial)
        assertEquals(listOf(1, 2, 0), found.map { it.season })
        assertEquals(listOf("Titre 1", "Titre 2"), found[0].episodes.map { it.title })
        assertEquals(listOf("Titre 3", "Titre 4"), found[1].episodes.map { it.title })
    }

    @Test fun aShowInSeasonsHasTheCurrentOneAtTheTop() {
        val p = parse("", item(1, 1, 1), item(2, 1, 2), item(3, 2, 1))
        val found = Seasons.shelves(p.episodes, p.serial)
        assertEquals(listOf(2, 1), found.map { it.season })
        assertEquals(listOf("Titre 2", "Titre 1"), found[1].episodes.map { it.title })
    }

    @Test fun oneSeasonAloneNeedsNoHeading() {
        assertTrue(Seasons.shelves(parse("", item(1, 1, 1), item(2, 1, 2)).episodes, false).isEmpty())
    }

    @Test fun theSeasonShownOpen() {
        val episodes = parse("", item(1, 1, 1), item(2, 2, 1), item(3, 3, 1)).episodes
        val found = Seasons.shelves(episodes, true)
        assertEquals(1, Seasons.open(found, episodes))
        val heard = episodes.map { if (it.season == 2) it.copy(lastPlayed = 5) else it }
        assertEquals(2, Seasons.open(found, heard))
    }

    @Test fun aTitleThatCannotBeWrittenIsWrittenWithoutWhatCannot() {
        val text = Opml.export(listOf(Feed(id = "a", url = "https://example.org/b.xml", title = "De\u0003ux & trois")))
        assertFalse(text.contains('\u0003'))
        val back = Opml.parse(text.byteInputStream(), parser())
        assertEquals(listOf(Opml.Line("https://example.org/b.xml", "Deux & trois")), back)
    }
}
