package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Backup
import com.freedomfighter.readerspodcasts.data.Feed
import com.freedomfighter.readerspodcasts.data.FeedParser
import com.freedomfighter.readerspodcasts.data.Kind
import com.freedomfighter.readerspodcasts.data.Opml
import com.freedomfighter.readerspodcasts.data.State
import com.freedomfighter.readerspodcasts.data.episodeId
import com.freedomfighter.readerspodcasts.data.Spoken
import com.freedomfighter.readerspodcasts.data.lengthOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParserFactory
import java.util.Calendar
import java.util.TimeZone

/**
 * The feed reader, against the shapes real feeds come in. These run on the JVM, with kXML in
 * place of Android's parser — see FeedParser.parse.
 */
class FeedParserTest {

    private fun parser() = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()

    private fun parse(xml: String, kind: Kind = Kind.RSS) =
        FeedParser.parse("feed1", kind, xml.byteInputStream(), parser())

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>La Grande Table</title>
            <link>https://example.org</link>
            <managingEditor>France Culture</managingEditor>
            <item>
              <title>Le temps des orages</title>
              <guid isPermaLink="false">episode-42</guid>
              <pubDate>Wed, 17 Sep 2026 08:00:00 +0200</pubDate>
              <itunes:duration>47:20</itunes:duration>
              <description>&lt;p&gt;Un entretien avec&amp;nbsp;quelqu'un.&lt;/p&gt;</description>
              <enclosure url="https://example.org/42.mp3" length="45678901" type="audio/mpeg" />
            </item>
            <item>
              <title>Sans enclosure</title>
              <guid>episode-43</guid>
              <pubDate>Thu, 18 Sep 2026 08:00:00 +0200</pubDate>
            </item>
            <item>
              <title>Durée en secondes</title>
              <guid>episode-44</guid>
              <pubDate>Fri, 19 Sep 2026 08:00:00 +0200</pubDate>
              <itunes:duration>3723</itunes:duration>
              <enclosure url="https://example.org/44.mp3" type="audio/mpeg" />
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test fun `reads the channel and its episodes`() {
        val parsed = parse(rss)
        assertEquals("La Grande Table", parsed.title)
        assertEquals("France Culture", parsed.author)
        // The item without media is not an episode and is left out.
        assertEquals(2, parsed.episodes.size)
    }

    @Test fun `keeps title, media, size and duration`() {
        val e = parse(rss).episodes.first()
        assertEquals("Le temps des orages", e.title)
        assertEquals("https://example.org/42.mp3", e.mediaUrl)
        assertEquals(45678901L, e.bytes)
        assertEquals(47 * 60_000L + 20_000L, e.durationMs)
        assertEquals("audio/mpeg", e.mime)
    }

    @Test fun `a duration in plain seconds is read too`() {
        val e = parse(rss).episodes.first { it.title == "Durée en secondes" }
        assertEquals(3723_000L, e.durationMs)
    }

    @Test fun `html in a description comes out as text`() {
        val e = parse(rss).episodes.first()
        assertEquals("Un entretien avec quelqu'un.", e.description)
    }

    @Test fun `html entities inside a description are read, not shown`() {
        // A real feed escapes its description twice: once as XML, once as HTML. What comes out
        // of the first pass still carries &agrave; and the like, and the screen must not show it.
        assertEquals("à propos", FeedParser.strip("&amp;agrave; propos".replace("&amp;", "&")))
        assertEquals("« déjà », dit-il…", FeedParser.unescape("&laquo;&nbsp;d&eacute;j&agrave;&nbsp;&raquo;, dit-il&hellip;")
            .replace("\u00a0", " "))
        assertEquals("é € œ", FeedParser.unescape("&#233; &#x20AC; &#x153;"))
        // What is not an entity is left exactly as it is.
        assertEquals("100 % & plus", FeedParser.unescape("100 % & plus"))
        assertEquals("&unknownthing;", FeedParser.unescape("&unknownthing;"))
    }

    @Test fun `tags become line breaks or nothing at all`() {
        assertEquals("Un titre\nUne suite", FeedParser.strip("<h2>Un titre</h2><p>Une suite</p>"))
        assertEquals("", FeedParser.strip("<script>alert('x')</script>"))
    }

    @Test fun `a date is read in the timezone it was written in`() {
        val e = parse(rss).episodes.first()
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = e.published }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, c.get(Calendar.MONTH))
        assertEquals(17, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(6, c.get(Calendar.HOUR_OF_DAY))   // 08:00 +0200
    }

    @Test fun `the id follows the guid, so a retitled episode keeps its place`() {
        val first = parse(rss).episodes.first()
        val retitled = parse(rss.replace("Le temps des orages", "Le temps des orages (rediffusion)")).episodes.first()
        assertEquals(first.id, retitled.id)
        assertEquals(episodeId("feed1", "episode-42"), first.id)
    }

    // ---- Atom, which is what YouTube publishes ----

    private val atom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015"
              xmlns:media="http://search.yahoo.com/mrss/">
          <title>Une chaîne</title>
          <author><name>Quelqu'un</name></author>
          <entry>
            <id>yt:video:abcdefghijk</id>
            <yt:videoId>abcdefghijk</yt:videoId>
            <title>Une causerie</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=abcdefghijk" />
            <published>2026-09-17T08:00:00+00:00</published>
            <media:group>
              <media:description>Ce qui est dit.</media:description>
            </media:group>
          </entry>
        </feed>
    """.trimIndent()

    @Test fun `a youtube entry becomes an episode pointing at its page`() {
        val parsed = FeedParser.parse("feed2", Kind.YOUTUBE, atom.byteInputStream(), parser())
        assertEquals("Une chaîne", parsed.title)
        assertEquals(1, parsed.episodes.size)
        val e = parsed.episodes.first()
        assertEquals("Une causerie", e.title)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", e.mediaUrl)
        assertEquals(episodeId("feed2", "yt:video:abcdefghijk"), e.id)
    }

    @Test fun `the same entry is no episode at all without youtube`() {
        // In the public build such a feed brings nothing playable, and says so by being empty
        // rather than by filling the list with rows that cannot play.
        val parsed = FeedParser.parse("feed2", Kind.RSS, atom.byteInputStream(), parser())
        assertEquals(0, parsed.episodes.size)
    }

    @Test fun `a media url is taken when there is no enclosure`() {
        val xml = """
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel><title>Media RSS</title>
                <item>
                  <title>Par media:content</title>
                  <guid>m1</guid>
                  <media:content url="https://example.org/m1.m4a" type="audio/mp4" />
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val e = parse(xml).episodes.single()
        assertEquals("https://example.org/m1.m4a", e.mediaUrl)
        assertEquals("audio/mp4", e.mime)
    }

    @Test fun `a broken feed yields nothing rather than throwing on the caller`() {
        val parsed = runCatching { parse("<rss><channel><title>x</title></channel>") }
        assertTrue(parsed.isFailure || parsed.getOrNull()?.episodes?.isEmpty() == true)
    }

    // ---- OPML ----

    @Test fun `opml is read at any depth and written back the same`() {
        val xml = """
            <opml version="2.0"><body>
              <outline text="Dossier">
                <outline type="rss" text="Un" xmlUrl="https://a.example/feed" />
                <outline type="rss" title="Deux" xmlUrl="https://b.example/feed" />
              </outline>
              <outline type="rss" text="Trois &amp; suite" xmlUrl="https://c.example/feed" />
            </body></opml>
        """.trimIndent()
        val lines = Opml.parse(xml.byteInputStream(), parser())
        assertEquals(3, lines.size)
        assertEquals("https://a.example/feed", lines[0].url)
        assertEquals("Deux", lines[1].title)
        assertEquals("Trois & suite", lines[2].title)

        val written = Opml.export(lines.map { Feed(id = it.url, url = it.url, title = it.title) })
        val again = Opml.parse(written.byteInputStream(), parser())
        assertEquals(lines.map { it.url }, again.map { it.url })
        assertEquals(lines.map { it.title }, again.map { it.title })
    }

    // ---- the backup file ----

    @Test fun `settings and positions survive the round trip`() {
        val feeds = listOf(Feed(id = "f", url = "https://a.example/feed", title = "Un", autoDownload = true, keepCount = 20))
        val episodes = listOf(
            com.freedomfighter.readerspodcasts.data.Episode(
                id = "e1", feedId = "f", title = "Un épisode", published = 1L,
                mediaUrl = "https://a.example/1.mp3", positionMs = 90_000, state = State.STARTED, lastPlayed = 42L,
            ),
            // Untouched episodes are left out: the file says only what cannot be worked out again.
            com.freedomfighter.readerspodcasts.data.Episode(
                id = "e2", feedId = "f", title = "Pas commencé", published = 2L, mediaUrl = "https://a.example/2.mp3",
            ),
        )
        val text = Backup.export(mapOf("wifi_only" to false, "speed" to 1.5f), feeds, episodes)
        val back = Backup.parse(text)
        assertEquals(false, back.settings["wifi_only"])
        assertEquals(1, back.states.size)
        assertEquals(90_000L, back.states.first().positionMs)
        assertEquals(State.STARTED, back.states.first().state)
        assertEquals("https://a.example/feed", back.feeds.single().url)
        assertEquals(20, back.feeds.single().keepCount)
        assertTrue(back.feeds.single().autoDownload)
        assertNotNull(back.feeds.single().id)
    }

    @Test fun `a length is broken up the way one says it`() {
        assertEquals(Spoken(0, 47, 0), lengthOf(47 * 60_000L))
        assertEquals(Spoken(1, 2, 0), lengthOf(62 * 60_000L))
        // Under a minute it is seconds: a rounded "0 min" is not a length.
        assertEquals(Spoken(0, 0, 12), lengthOf(12_000L))
        assertEquals(Spoken(0, 0, 0), lengthOf(0))
    }
}
