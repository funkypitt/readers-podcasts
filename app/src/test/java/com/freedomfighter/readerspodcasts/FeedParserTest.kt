package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Backup
import com.freedomfighter.readerspodcasts.data.Feed
import com.freedomfighter.readerspodcasts.data.FeedParser
import com.freedomfighter.readerspodcasts.data.Kind
import com.freedomfighter.readerspodcasts.data.Opml
import com.freedomfighter.readerspodcasts.data.State
import com.freedomfighter.readerspodcasts.data.episodeId
import com.freedomfighter.readerspodcasts.data.Spoken
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.channelsByLatest
import com.freedomfighter.readerspodcasts.data.feedLanguage
import com.freedomfighter.readerspodcasts.data.autoDeletable
import com.freedomfighter.readerspodcasts.data.Chapters
import com.freedomfighter.readerspodcasts.data.lengthOf
import com.freedomfighter.readerspodcasts.data.Prefs
import com.freedomfighter.readerspodcasts.ui.clipboardUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
              <media:content url="https://www.youtube.com/v/abcdefghijk?version=3" type="application/x-shockwave-flash" width="640" height="390" />
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
        // The watch page, not the Flash embed the feed also carries: yt-dlp is given the page.
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

    @Test fun `a feed that repeats a guid yields one episode, not two of the same id`() {
        // Real shape, from the Dharma Seed series: two different talks under one guid. Two rows
        // with the same id are a list Compose refuses to draw — it throws on the key — so the
        // parser must not hand them out in the first place.
        val xml = """
            <rss version="2.0"><channel><title>Notable Dhamma Teachers</title>
              <item>
                <title>Dharma In The Workplace</title><guid>talk-1</guid>
                <pubDate>Wed, 17 Sep 2026 08:00:00 +0000</pubDate>
                <enclosure url="https://example.org/a.mp3" type="audio/mpeg" />
              </item>
              <item>
                <title>Dharma In The Workplace — alternate</title><guid>talk-1</guid>
                <pubDate>Tue, 16 Sep 2026 08:00:00 +0000</pubDate>
                <enclosure url="https://example.org/b.mp3" type="audio/mpeg" />
              </item>
            </channel></rss>
        """.trimIndent()
        val episodes = parse(xml).episodes
        assertEquals(1, episodes.size)
        assertEquals(episodes.map { it.id }.distinct().size, episodes.size)
        // The first one listed is the one kept: feeds put their newest at the top.
        assertEquals("Dharma In The Workplace", episodes.single().title)
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
            Episode(
                id = "e1", feedId = "f", title = "Un épisode", published = 1L,
                mediaUrl = "https://a.example/1.mp3", positionMs = 90_000, state = State.STARTED, lastPlayed = 42L,
            ),
            // Untouched episodes are left out: the file says only what cannot be worked out again.
            Episode(
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

    @Test fun `channels sort by their latest episode, including those that have none`() {
        // The crash this covers: a channel with no episode gave 0 as an Int among the Longs, and
        // sorting threw ClassCastException at launch. Six of one user's feeds are like that —
        // a page that is not a feed, a show taken down, a podcast that has published nothing.
        val a = Feed(id = "a", url = "https://a.example/f", title = "Ancienne")
        val b = Feed(id = "b", url = "https://b.example/f", title = "Récente")
        val c = Feed(id = "c", url = "https://c.example/f", title = "Vide")
        val d = Feed(id = "d", url = "https://d.example/f", title = "Aussi vide")
        val episodes = listOf(
            Episode(id = "1", feedId = "a", title = "vieux", published = 1_000L, mediaUrl = "x"),
            Episode(id = "2", feedId = "b", title = "neuf", published = 9_000L, mediaUrl = "x"),
            Episode(id = "3", feedId = "a", title = "moins vieux", published = 2_000L, mediaUrl = "x"),
        )
        val order = channelsByLatest(listOf(a, b, c, d), episodes).map { it.title }
        assertEquals(listOf("Récente", "Ancienne", "Aussi vide", "Vide"), order)
        // And with nothing at all, which is what a fresh install looks like.
        assertEquals(2, channelsByLatest(listOf(c, d), emptyList()).size)
    }

    @Test fun `a view stored by an older version still names a list that exists`() {
        // 0.1 wrote "queue" and "new"; 0.1.1 knows neither, and a screen whose title said
        // "channels" while the list stayed empty is what an update looked like.
        assertEquals(Prefs.VIEW_CHANNELS, Prefs.viewOrChannels("queue", false))
        assertEquals(Prefs.VIEW_EPISODES, Prefs.viewOrChannels("new", false))
        assertEquals(Prefs.VIEW_FAVOURITES, Prefs.viewOrChannels(Prefs.VIEW_FAVOURITES, false))
        // A feed's id is a view too, as long as that feed is still subscribed.
        assertEquals("abc123", Prefs.viewOrChannels("abc123", true))
        assertEquals(Prefs.VIEW_CHANNELS, Prefs.viewOrChannels("abc123", false))
        assertEquals(Prefs.VIEW_CHANNELS, Prefs.viewOrChannels(null, false))
    }

    @Test fun `the clipboard is offered only when it holds an address`() {
        assertEquals("https://example.org/feed.xml", clipboardUrl(" https://example.org/feed.xml "))
        assertEquals("feed://example.org/x", clipboardUrl("feed://example.org/x"))
        // What is in a clipboard is usually a sentence, a word, or nothing at all.
        assertEquals("", clipboardUrl("La Grande Table du 17 septembre"))
        assertEquals("", clipboardUrl(null))
        assertEquals("", clipboardUrl("example.org/feed.xml"))
        assertEquals("", clipboardUrl("https://a b.example/feed"))
    }

    @Test fun `a transcript is cut into passages that keep their place in the sound`() {
        // The translation is aligned on blocks, not on sentences: a block keeps the start of its
        // first line and the end of its last, which is an alignment the reading can trust.
        val segments = (0 until 40).map {
            com.freedomfighter.readers.speech.whisper.Segment(
                it * 3_000L, it * 3_000L + 2_800L, "Une phrase de longueur ordinaire, numéro $it.",
            )
        }
        val blocks = com.freedomfighter.readers.speech.translate.Translator.group(segments)
        assertTrue(blocks.size in 2..8)
        assertEquals(0L, blocks.first().startMs)
        assertEquals(segments.last().endMs, blocks.last().endMs)
        // Every block follows the one before, and none is empty.
        blocks.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
        assertTrue(blocks.all { it.text.isNotBlank() })
        // Nothing is lost on the way.
        assertEquals(40, blocks.sumOf { b -> Regex("numéro \\d+").findAll(b.text).count() })
    }

    @Test fun `the primer the model repeats back is taken off`() {
        val t = com.freedomfighter.readers.speech.translate.Translator
        assertEquals("Voici la suite.", t.strip("TRADUCTION EN FRANÇAIS : Voici la suite."))
        assertEquals("Here it is.", t.strip("\"Here it is.\""))
        assertEquals("Rien à retirer.", t.strip("  Rien à retirer.  "))
    }

    @Test fun `a length is broken up the way one says it`() {
        assertEquals(Spoken(0, 47, 0), lengthOf(47 * 60_000L))
        assertEquals(Spoken(1, 2, 0), lengthOf(62 * 60_000L))
        // Under a minute it is seconds: a rounded "0 min" is not a length.
        assertEquals(Spoken(0, 0, 12), lengthOf(12_000L))
        assertEquals(Spoken(0, 0, 0), lengthOf(0))
    }

    @Test fun aChannelSuggestsTheLanguageItWasLastWrittenDownIn() {
        val episodes = listOf(
            Episode(id = "a", feedId = "f", title = "vieux", mediaUrl = "http://x/a.mp3", published = 100, transcript = true, transcriptLanguage = "de"),
            Episode(id = "b", feedId = "f", title = "récent", mediaUrl = "http://x/b.mp3", published = 200, transcript = true, transcriptLanguage = "fr"),
            // Never written down, and another channel's: neither should count.
            Episode(id = "c", feedId = "f", title = "jamais", mediaUrl = "http://x/c.mp3", published = 300),
            Episode(id = "d", feedId = "g", title = "ailleurs", mediaUrl = "http://x/d.mp3", published = 400, transcript = true, transcriptLanguage = "ru"),
        )
        assertEquals("fr", feedLanguage(episodes, "f"))
        assertEquals("ru", feedLanguage(episodes, "g"))
        assertEquals(null, feedLanguage(episodes, "h"))
        assertEquals(null, feedLanguage(emptyList(), "f"))
    }

    @Test fun whatIsWorthKeepingIsNotThrownAwayOnceHeard() {
        val plain = Episode(id = "a", feedId = "f", title = "t", mediaUrl = "http://x/a.mp3", published = 1)
        assertTrue(autoDeletable(plain, Kind.RSS))
        assertFalse("un favori se garde", autoDeletable(plain.copy(starred = true), Kind.RSS))
        assertFalse("un épisode mis par écrit se garde", autoDeletable(plain.copy(transcript = true), Kind.RSS))
        assertFalse("YouTube n'a pas de fichier à reprendre", autoDeletable(plain, Kind.YOUTUBE))
        assertTrue("une chaîne inconnue ne protège rien à elle seule", autoDeletable(plain, null))
    }

    @Test fun chaptersAreReadFromTheDescriptionInBothCustoms() {
        val youtube = """
            Une causerie sur l'écoute.

            0:00 Introduction
            2:14 Ce qu'on entend
            1:02:03 Questions
        """.trimIndent()
        val chapters = Chapters.parse(youtube)
        assertEquals(3, chapters.size)
        assertEquals("Introduction", chapters[0].title)
        assertEquals(134_000L, chapters[1].startMs)
        assertEquals(3_723_000L, chapters[2].startMs)

        val podcast = """
            (00:00) Générique
            (05:30) — L'invité
            (41:07) Fin
        """.trimIndent()
        assertEquals(listOf("Générique", "L'invité", "Fin"), Chapters.parse(podcast).map { it.title })

        val tail = "Introduction - 0:00\nLe sujet — 03:12\nConclusion (58:00)"
        assertEquals(listOf(0L, 192_000L, 3_480_000L), Chapters.parse(tail).map { it.startMs })
    }

    @Test fun proseThatNamesATimeIsNotAListOfChapters() {
        assertTrue(Chapters.parse("Il en parle à 12:30, c'est le meilleur passage.").isEmpty())
        // Out of order: a mention, not a table of contents.
        assertTrue(Chapters.parse("10:00 la fin\n2:00 le début").isEmpty())
        // Past the end of the episode.
        assertTrue(Chapters.parse("0:00 Un\n45:00 Deux", durationMs = 600_000).isEmpty())
        assertTrue(Chapters.parse("0:00 Un\n45:00 Deux", durationMs = 3_600_000).size == 2)
        assertTrue(Chapters.parse("").isEmpty())
        assertTrue(Chapters.parse("0:00 Un").isEmpty())
    }

    @Test fun theChapterOneIsInsideIsTheLastOneBegun() {
        val chapters = Chapters.parse("0:00 Un\n2:00 Deux\n4:00 Trois")
        assertEquals("Un", Chapters.at(chapters, 0)?.title)
        assertEquals("Un", Chapters.at(chapters, 119_000)?.title)
        assertEquals("Deux", Chapters.at(chapters, 120_000)?.title)
        assertEquals("Trois", Chapters.at(chapters, 999_000)?.title)
    }
}
