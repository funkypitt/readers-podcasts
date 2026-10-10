package com.freedomfighter.readerspodcasts.data

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What was said in an episode, as a small book: the form Reader's Books reads, so that a
 * transcript can be read there page by page, highlighted and commented like any other book of
 * the library.
 *
 * One chapter, the paragraphs of the transcript as they leave the app (see [Transcripts.asText]).
 * The book names the episode as its title and the channel as its author; a first line in small
 * print says where it comes from. Nothing in it changes once written: a highlight is a place in
 * the text, and a text that moved under it would lose it.
 */
object Epub {

    fun esc(s: String): String = buildString {
        s.forEach { c ->
            when {
                c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;"); c == '"' -> append("&quot;")
                c < ' ' && c != '\n' && c != '\t' -> Unit       // what XML does not allow at all
                else -> append(c)
            }
        }
    }

    /** A name the drive, the phone and a desktop all accept. */
    fun fileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(120).trim().ifEmpty { "untitled" }

    /**
     * [id] makes the book's identifier, [language] is the two-letter code of the text, [source]
     * the small line under the title (channel, date), [text] the paragraphs separated by blank lines.
     */
    fun build(id: String, title: String, author: String, language: String, source: String, text: String): ByteArray {
        val paragraphs = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val body = buildString {
            append("<h1>").append(esc(title)).append("</h1>\n")
            if (source.isNotBlank()) append("<p class=\"author\">").append(esc(source)).append("</p>\n")
            paragraphs.forEach { append("<p>").append(esc(it).replace("\n", "<br/>")).append("</p>\n") }
        }
        val lang = esc(language.ifBlank { "und" })
        val page = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$lang" lang="$lang">
<head><meta charset="utf-8"/><title>${esc(title)}</title></head>
<body>
$body</body>
</html>
"""
        val nav = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><meta charset="utf-8"/><title>${esc(title)}</title></head>
<body><nav epub:type="toc"><ol><li><a href="text.xhtml">${esc(title)}</a></li></ol></nav></body>
</html>
"""
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">readers-podcasts:${esc(id)}</dc:identifier>
<dc:title>${esc(title)}</dc:title>
<dc:creator>${esc(author)}</dc:creator>
<dc:language>$lang</dc:language>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="text" href="text.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="text"/></spine>
</package>
"""
        val container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // The type first and stored as it is: that is how a reader knows an EPUB without opening it.
            val type = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = type.size.toLong(); compressedSize = type.size.toLong()
                crc = CRC32().apply { update(type) }.value; time = 0
            })
            zip.write(type); zip.closeEntry()
            listOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/nav.xhtml" to nav, "OEBPS/text.xhtml" to page).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
