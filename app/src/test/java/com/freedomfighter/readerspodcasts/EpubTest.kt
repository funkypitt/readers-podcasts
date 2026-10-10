package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Epub
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Line
import com.freedomfighter.readerspodcasts.data.ShelfAccount
import com.freedomfighter.readerspodcasts.data.Transcripts
import com.freedomfighter.readerspodcasts.net.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** The small book a transcript leaves as, and what decides that it is sent. */
class EpubTest {
    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; out[e.name] = zip.readBytes().decodeToString() }
        }
        return out
    }

    @Test fun aTranscriptIsABookReadersBooksCanRead() {
        val lines = listOf(Line(0, 900, "Bonjour & bienvenue."), Line(1000, 2000, "On parle <ici> de l'été."), Line(5000, 6000, "Second paragraphe."))
        val bytes = Epub.build("abc", "Épisode 12 : « l'été »", "La Chaîne", "fr", "La Chaîne · 3 mars 2026", Transcripts.asText(lines))
        val files = entries(bytes)
        assertEquals(listOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml", "OEBPS/text.xhtml"), files.keys.toList())
        assertEquals("application/epub+zip", files["mimetype"])
        assertTrue(files.getValue("OEBPS/content.opf").contains("<dc:title>Épisode 12 : « l'été »</dc:title>"))
        assertTrue(files.getValue("OEBPS/content.opf").contains("<dc:creator>La Chaîne</dc:creator>"))
        val page = files.getValue("OEBPS/text.xhtml")
        assertTrue(page.contains("<p class=\"author\">La Chaîne · 3 mars 2026</p>"))
        assertTrue(page.contains("<p>Bonjour &amp; bienvenue. On parle &lt;ici&gt; de l'été.</p>\n<p>Second paragraphe.</p>"))
        // the same text gives the same book, byte for byte: nothing in it depends on the day it is made
        assertTrue(bytes.contentEquals(Epub.build("abc", "Épisode 12 : « l'été »", "La Chaîne", "fr", "La Chaîne · 3 mars 2026", Transcripts.asText(lines))))
        System.getProperty("epub.out")?.let { File(it).writeBytes(bytes) }
    }

    @Test fun aNameTheDriveAccepts() {
        assertEquals("Un titre avec tout", Epub.fileName("Un: titre / avec \"tout\"?."))
        assertEquals("untitled", Epub.fileName(" .. "))
        assertEquals(120, Epub.fileName("a".repeat(300)).length)
    }

    @Test fun whatIsStillToSend() {
        val e = Episode(id = "1", feedId = "f", title = "t", published = 0, mediaUrl = "m")
        assertEquals("", Shelf.wanted(e))
        assertEquals("t", Shelf.wanted(e.copy(transcript = true)))
        assertEquals("t+fr", Shelf.wanted(e.copy(transcript = true, translation = "fr")))
    }

    @Test fun theLibraryAccountFromACredentialsFile() {
        assertNull(ShelfAccount.read("""{"hello": 1}"""))
        assertEquals(ShelfAccount("https://d/", "u", "p"), ShelfAccount.read("""{"format":"readers-credentials","readers-books":{"url":"https://d/","username":"u","password":"p"},"readers-notes":{"server":"https://n/"}}"""))
        assertEquals("https://n/", ShelfAccount.read("""{"format":"readers-credentials","readers-notes":{"server":"https://n/","folder":"Notes","username":"u","password":"p"}}""")!!.url)
        assertEquals("", ShelfAccount.read("""{"format":"readers-credentials","readers-tasks":{"url":"https://t/"}}""")!!.url)
    }
}
