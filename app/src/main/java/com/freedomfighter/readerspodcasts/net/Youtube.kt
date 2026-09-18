package com.freedomfighter.readerspodcasts.net

/**
 * YouTube addresses.
 *
 * A subscription to a channel needs nothing special: YouTube publishes
 * `feeds/videos.xml?channel_id=…`, which is an ordinary Atom feed. Only the media differs —
 * an entry points at a watch page, not at an audio file — and turning that page into audio is
 * what the private build adds in 0.2. Recognising the address is done in both builds, so the
 * public one can say plainly that it does not do this rather than fetch something empty.
 */
object Youtube {

    fun looksLikeYoutube(url: String): Boolean {
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
        return host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith(".youtube.com")
    }

    /** True for an address that is already the Atom feed: nothing to resolve. */
    private fun isFeed(url: String) = url.contains("/feeds/videos.xml", true)

    /**
     * The channel's feed from whatever form of address was pasted. A `/channel/UC…` or a
     * playlist gives it away; a `@handle`, a `/c/` or a `/user/` name does not, and the page
     * has to be read for the channel id it carries.
     */
    fun channelFeed(url: String): String? {
        if (isFeed(url)) return url
        Regex("/channel/(UC[\\w-]{20,})", RegexOption.IGNORE_CASE).find(url)?.let {
            return "https://www.youtube.com/feeds/videos.xml?channel_id=${it.groupValues[1]}"
        }
        Regex("[?&]list=([\\w-]+)").find(url)?.let {
            return "https://www.youtube.com/feeds/videos.xml?playlist_id=${it.groupValues[1]}"
        }
        val id = channelIdFromPage(url) ?: return null
        return "https://www.youtube.com/feeds/videos.xml?channel_id=$id"
    }

    /** The channel id as the page itself states it — the only way to resolve a handle. */
    private fun channelIdFromPage(url: String): String? {
        val c = runCatching { Net.open(url) }.getOrNull() ?: return null
        return try {
            if (c.responseCode >= 400) return null
            val html = Net.body(c).bufferedReader().use { reader ->
                // The id sits in the head of the document; reading the whole page of a channel
                // would pull megabytes over the connection for nothing.
                val buf = CharArray(256 * 1024)
                val n = reader.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }
            Regex("\"(?:externalId|channelId)\"\\s*:\\s*\"(UC[\\w-]{20,})\"").find(html)?.groupValues?.get(1)
                ?: Regex("channel_id=(UC[\\w-]{20,})").find(html)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { c.disconnect() }
        }
    }
}
