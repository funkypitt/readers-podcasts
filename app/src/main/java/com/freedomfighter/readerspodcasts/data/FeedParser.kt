package com.freedomfighter.readerspodcasts.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * RSS 2.0, Atom and Media RSS read in one pass.
 *
 * Namespaces are left unprocessed on purpose: podcast feeds in the wild declare `itunes:`,
 * `media:` and `yt:` in ways a strict reader trips over, and comparing the raw tag name is
 * both shorter and harder to break. Anything the parser does not recognise is skipped rather
 * than refused — a feed is never rejected for carrying an oddity in a corner.
 */
object FeedParser {

    data class Parsed(val title: String, val author: String, val episodes: List<Episode>)

    private class Building {
        var title = ""
        var guid = ""
        var date = 0L
        var enclosure = ""
        var enclosureType = ""
        var bytes = 0L
        var mediaContent = ""
        var mediaType = ""
        var link = ""
        var durationMs = 0L
        var description = ""
    }

    /**
     * [parser] is handed in only by the tests, which run on a plain JVM where `android.util.Xml`
     * is a stub: a feed reader one cannot test without a phone is a feed reader one does not fix.
     */
    fun parse(feedId: String, kind: Kind, input: InputStream, parser: XmlPullParser? = null): Parsed {
        val p = parser ?: Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        var feedTitle = ""
        var feedAuthor = ""
        var inItem = false
        var item = Building()
        val out = ArrayList<Episode>()
        // Atom puts <title> and <author> at feed level and again in every entry; only what is
        // read outside an entry belongs to the feed itself.
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = local(p.name)
                    when {
                        tag == "item" || tag == "entry" -> { inItem = true; item = Building() }
                        inItem -> readItemTag(p, tag, item)
                        tag == "title" && feedTitle.isEmpty() -> feedTitle = text(p).trim()
                        tag == "author" && feedAuthor.isEmpty() -> feedAuthor = text(p).trim()
                        tag == "managingEditor" && feedAuthor.isEmpty() -> feedAuthor = text(p).trim()
                        tag == "name" && feedAuthor.isEmpty() -> feedAuthor = text(p).trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = local(p.name)
                    if (tag == "item" || tag == "entry") {
                        inItem = false
                        build(feedId, kind, item)?.let { out.add(it) }
                    }
                }
            }
            event = p.next()
        }
        // Feeds do repeat a guid — two different talks under one id, in several of the Dharma
        // Seed series and in more than one news feed. Two episodes with the same id are a list
        // that cannot be drawn at all, so the first one wins and the other is dropped here,
        // rather than further down where it would take a screen with it.
        return Parsed(feedTitle, feedAuthor, out.distinctBy { it.id })
    }

    private fun readItemTag(p: XmlPullParser, tag: String, item: Building) {
        when (tag) {
            "title" -> if (item.title.isEmpty()) item.title = text(p).trim()
            "guid", "id" -> if (item.guid.isEmpty()) item.guid = text(p).trim()
            "videoId" -> if (item.guid.isEmpty()) item.guid = text(p).trim()
            "pubDate", "published", "updated", "date" -> if (item.date == 0L) item.date = date(text(p))
            "enclosure" -> {
                item.enclosure = p.getAttributeValue(null, "url") ?: ""
                item.enclosureType = p.getAttributeValue(null, "type") ?: ""
                item.bytes = p.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
            }
            "content" -> {
                // Atom's <content> carries text, Media RSS's <media:content> carries a url.
                val url = p.getAttributeValue(null, "url")
                if (url != null && item.mediaContent.isEmpty()) {
                    item.mediaContent = url
                    item.mediaType = p.getAttributeValue(null, "type") ?: ""
                } else if (url == null && item.description.isEmpty()) {
                    item.description = strip(text(p))
                }
            }
            "link" -> {
                // RSS writes the url as text, Atom as an href; only the readable page is wanted.
                val href = p.getAttributeValue(null, "href")
                val rel = p.getAttributeValue(null, "rel")
                if (href != null) { if ((rel == null || rel == "alternate") && item.link.isEmpty()) item.link = href }
                else if (item.link.isEmpty()) item.link = text(p).trim()
            }
            "duration" -> if (item.durationMs == 0L) item.durationMs = duration(text(p))
            "description", "summary" -> if (item.description.isEmpty()) item.description = strip(text(p))
        }
    }

    /** An item without playable media is not an episode; everything else gets an id that lasts. */
    private fun build(feedId: String, kind: Kind, b: Building): Episode? {
        val media = when {
            b.enclosure.isNotBlank() -> b.enclosure
            // A YouTube entry has no media of its own: the page is what gets handed to yt-dlp.
            // Its <media:content> is the Flash embed of fifteen years ago
            // (`youtube.com/v/ID?version=3`), and handing *that* to yt-dlp is how a download
            // ended without a file and without a word.
            kind == Kind.YOUTUBE && b.link.isNotBlank() -> b.link
            b.mediaContent.isNotBlank() && b.mediaContent.startsWith("http") &&
                !b.mediaType.contains("flash", true) -> b.mediaContent
            b.link.isNotBlank() && kind == Kind.YOUTUBE -> b.link
            else -> return null
        }
        if (b.title.isBlank()) return null
        val guid = listOf(b.guid, media, b.title + b.date).first { it.isNotBlank() }
        val type = listOf(b.enclosureType, b.mediaType).firstOrNull { it.isNotBlank() } ?: "audio/*"
        return Episode(
            id = episodeId(feedId, guid), feedId = feedId, title = b.title,
            published = if (b.date > 0) b.date else System.currentTimeMillis(),
            mediaUrl = media, mime = type, bytes = b.bytes, durationMs = b.durationMs,
            description = b.description.take(2000),
        )
    }

    private fun local(name: String?): String = (name ?: "").substringAfterLast(':')

    private fun text(p: XmlPullParser): String = runCatching { p.nextText() }.getOrDefault("")

    /**
     * Feeds put HTML in their summaries, and that HTML carries its own entities — a description
     * arrives escaped once as XML and once again as HTML, so `&amp;agrave;` reaches us as
     * `&agrave;` and would otherwise be read out on the screen exactly like that.
     */
    fun strip(html: String): String = unescape(
        html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            // Every block that closes ends a line — not just </p>, or a description built of
            // headings and lists comes out as one run-on sentence.
            .replace(Regex("(?i)<br\\s*/?>|</(p|div|h[1-6]|li|ul|ol|blockquote|tr|section|article)>"), "\n")
            .replace(Regex("<[^>]+>"), "")
    ).lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()

    /** The named entities French and English feeds actually use, and every numeric one. */
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "agrave" to "à", "acirc" to "â", "aacute" to "á", "auml" to "ä", "aring" to "å", "atilde" to "ã",
        "ccedil" to "ç", "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë",
        "iacute" to "í", "icirc" to "î", "iuml" to "ï", "igrave" to "ì",
        "oacute" to "ó", "ocirc" to "ô", "ouml" to "ö", "ograve" to "ò", "otilde" to "õ", "oslash" to "ø",
        "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "ugrave" to "ù",
        "ntilde" to "ñ", "szlig" to "ß", "yuml" to "ÿ",
        "Agrave" to "À", "Acirc" to "Â", "Ccedil" to "Ç", "Eacute" to "É", "Egrave" to "È", "Ecirc" to "Ê",
        "Ouml" to "Ö", "Uuml" to "Ü", "Auml" to "Ä",
        "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "middot" to "·", "bull" to "•",
        "deg" to "°", "euro" to "€", "pound" to "£", "copy" to "©", "reg" to "®", "trade" to "™",
        "times" to "×",
    )

    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

    fun unescape(text: String): String = entity.replace(text) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x", true) -> body.drop(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
            else -> named[body] ?: m.value
        }
    }

    private fun codePoint(n: Int): String? =
        if (n in 1..0x10FFFF) String(Character.toChars(n)) else null

    /** `1:02:03`, `3723`, `02:03` — all three are current in the wild. */
    private fun duration(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0
        if (':' !in s) return (s.toDoubleOrNull() ?: return 0).toLong() * 1000
        val parts = s.split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> 0
        }
    }

    // RFC 822 in all the shapes podcast feeds use, then ISO 8601 for Atom.
    private val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm Z",
        "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd",
    )

    private fun date(raw: String): Long {
        val s = raw.trim().ifEmpty { return 0 }
        for (f in formats) {
            val parsed = runCatching {
                SimpleDateFormat(f, Locale.US).apply {
                    isLenient = true
                    if (f.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC")
                }.parse(s)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0
    }
}
