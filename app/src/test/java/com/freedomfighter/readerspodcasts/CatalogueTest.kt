package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.net.Catalogue
import com.freedomfighter.readerspodcasts.net.Catalogue.Found
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.freedomfighter.readerspodcasts.ui.cut
import com.freedomfighter.readerspodcasts.ui.looksLikeAddress

/**
 * The two directories, read from answers they really gave (trimmed to the fields that matter,
 * saved 2026-09-23 — the same files feed the desktop's tests), and put together.
 */
class CatalogueTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun appleAnswerIsRead() {
        val found = Catalogue.parseApple(resource("catalogue-apple.json"))
        assertEquals(4, found.size)
        assertEquals("https://www.dharmaseed.org/feeds/recordings/", found[0].url)
        assertTrue(found[0].title.startsWith("Dharma Seed"))
        assertTrue(found[0].author.isNotBlank())
        assertTrue(found[0].episodes > 0)
    }

    @Test fun fyydAnswerIsRead() {
        val found = Catalogue.parseFyyd(resource("catalogue-fyyd.json"))
        assertEquals(6, found.size)
        assertEquals("Dharmabytes from free buddhist audio", found[0].title)
        assertEquals("Free Buddhist Audio", found[0].author)
        assertEquals("https://rss.libsyn.com/shows/185039/destinations/1271147.xml", found[0].url)
        // A licence where the author should be is no author.
        assertEquals("", found.first { it.url.contains("zbb.dharmaseed") }.author)
    }

    @Test fun entriesWithoutFeedAreSkipped() {
        val apple = """{"resultCount":2,"results":[{"collectionName":"No feed"},{"collectionName":"A","feedUrl":"https://a.example/f"}]}"""
        assertEquals(listOf("A"), Catalogue.parseApple(apple).map { it.title })
        val fyyd = """{"status":1,"data":[{"title":"","xmlURL":"https://x"},{"title":"B","xmlURL":"https://b.example/f"}]}"""
        assertEquals(listOf("B"), Catalogue.parseFyyd(fyyd).map { it.title })
        assertEquals(emptyList<Found>(), Catalogue.parseFyyd("""{"status":0,"msg":"error"}"""))
    }

    @Test fun keyFoldsSpellingsOfOneFeed() {
        val a = Catalogue.key("https://AV.dharmaseed.org/feeds/recordings/")
        assertEquals(a, Catalogue.key("http://av.dharmaseed.org/feeds/recordings"))
        assertEquals(Catalogue.key("https://www.dharmaseed.org/feeds/recordings/"), Catalogue.key("https://dharmaseed.org/feeds/recordings/"))
        // The path keeps its case: servers are allowed to tell /Feed from /feed.
        assertTrue(Catalogue.key("https://x.org/Feed") != Catalogue.key("https://x.org/feed"))
    }

    @Test fun mergeTakesTurnsAndKeepsOneOfEach() {
        val apple = Catalogue.parseApple(resource("catalogue-apple.json"))
        val fyyd = Catalogue.parseFyyd(resource("catalogue-fyyd.json"))
        val merged = Catalogue.merge("", apple, fyyd)
        // www. and a capitalised host were spelling the same two feeds twice.
        assertEquals(2 + 6, merged.size)
        assertEquals(merged.size, merged.map { Catalogue.key(it.url) }.toSet().size)
        assertEquals(apple[0].url, merged[0].url)
        assertEquals(fyyd[0].url, merged[1].url)
    }

    @Test fun mergeFillsWhatOneSideLacks() {
        val a = listOf(Found("Show", "", "https://s.example/feed", 0))
        val b = listOf(Found("Show", "Someone", "http://www.s.example/feed/", 12))
        val merged = Catalogue.merge("show", a, b)
        assertEquals(1, merged.size)
        assertEquals("https://s.example/feed", merged[0].url)
        assertEquals("Someone", merged[0].author)
        assertEquals(12, merged[0].episodes)
    }

    @Test fun namesHoldingTheQueryComeFirst() {
        val a = listOf(Found("Sleep stories", "", "https://1"), Found("Les Causeries de Léna", "", "https://2"))
        val b = listOf(Found("Causeries méditées", "", "https://3"), Found("Méditation causeries", "", "https://4"))
        val merged = Catalogue.merge("causeries medit", a, b).map { it.url }
        assertEquals(listOf("https://3", "https://4", "https://1", "https://2"), merged)
    }

    @Test fun addressesAreBuiltEscaped() {
        assertTrue(Catalogue.appleUrl("das podcast ufo", "CH").endsWith("&term=das+podcast+ufo&country=ch"))
        assertTrue(!Catalogue.appleUrl("x").contains("country"))
        assertTrue(Catalogue.fyydUrl("méditation").endsWith("term=m%C3%A9ditation"))
    }

    /** The one field of the + screen: an address stays an address, a name goes to the directories. */
    @Test fun addressOrName() {
        assertTrue(looksLikeAddress("https://dharmaseed.org/feeds/recordings/"))
        assertTrue(looksLikeAddress("feed://example.org/rss"))
        assertTrue(looksLikeAddress("dharmaseed.org/feeds/recordings"))
        assertTrue(looksLikeAddress("example.com"))
        assertTrue(!looksLikeAddress("dharma seed"))
        assertTrue(!looksLikeAddress("causeries"))
        assertTrue(!looksLikeAddress("Mr. Robot"))
        assertTrue(!looksLikeAddress(""))
    }

    @Test fun secondaryLineIsCut() {
        assertEquals("Free Buddhist Audio", cut("Free Buddhist Audio"))
        val long = cut("x".repeat(60))
        assertEquals(44, long.length)
        assertTrue(long.endsWith("…"))
    }
}
