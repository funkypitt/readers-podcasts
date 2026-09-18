package com.freedomfighter.readerspodcasts.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * OPML 2.0, the one thing every podcast app agrees on. Subscriptions leave the app in a file
 * any other reader takes, and arrive the same way — so nothing here is a one-way door.
 */
object Opml {

    data class Line(val url: String, val title: String)

    fun export(feeds: List<Feed>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n  <head>\n    <title>Reader's Podcasts</title>\n  </head>\n  <body>\n")
        feeds.forEach { f ->
            val title = esc(f.title.ifBlank { f.url })
            append("    <outline type=\"rss\" text=\"$title\" title=\"$title\" xmlUrl=\"${esc(f.url)}\" />\n")
        }
        append("  </body>\n</opml>\n")
    }

    /**
     * Every outline carrying an `xmlUrl`, at whatever depth: exporters nest subscriptions in
     * folders, and a reader that only looked at the top level would silently import nothing.
     */
    fun parse(input: InputStream, parser: XmlPullParser? = null): List<Line> {
        val p = parser ?: Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val out = ArrayList<Line>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name.equals("outline", true)) {
                val url = p.getAttributeValue(null, "xmlUrl") ?: p.getAttributeValue(null, "xmlurl")
                if (!url.isNullOrBlank()) {
                    val title = p.getAttributeValue(null, "title") ?: p.getAttributeValue(null, "text") ?: url
                    out.add(Line(url.trim(), title.trim()))
                }
            }
            event = p.next()
        }
        return out.distinctBy { it.url }
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
