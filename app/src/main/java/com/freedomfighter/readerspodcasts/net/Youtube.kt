package com.freedomfighter.readerspodcasts.net

/**
 * YouTube addresses.
 *
 * A subscription to a channel needs nothing special: YouTube publishes
 * `feeds/videos.xml?channel_id=…`, which is an ordinary Atom feed. Only the media differs — an
 * entry points at a watch page, not at an audio file — and turning that page into audio is what
 * the private build does with yt-dlp.
 *
 * Recognising the address is done in both builds, so the public one can say plainly that it
 * does not do this rather than fetch something empty.
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
     * playlist gives it away; a `@handle`, a `/c/` or a `/user/` name does not, and the page has
     * to be read for the id it carries.
     */
    fun channelFeed(url: String): String? {
        if (isFeed(url)) return url
        Regex("/channel/(UC[\\w-]{20,})", RegexOption.IGNORE_CASE).find(url)?.let {
            return feedOf(it.groupValues[1])
        }
        Regex("[?&]list=([\\w-]+)").find(url)?.let {
            return "https://www.youtube.com/feeds/videos.xml?playlist_id=${it.groupValues[1]}"
        }
        return channelIdFromPage(url)?.let { feedOf(it) }
    }

    private fun feedOf(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    /**
     * The channel id as the page itself states it.
     *
     * A channel page is well over two megabytes and says its own id late — past the seven
     * hundredth kilobyte, in the link to its RSS feed. So the page is read in pieces and
     * scanned as it comes, and the connection is cut the moment the id turns up; reading a
     * fixed head of it found nothing, and closing a half-read stream left the connection
     * draining the rest for as long as it took.
     */
    private fun channelIdFromPage(url: String): String? {
        val c = runCatching { Net.open(url) }.getOrNull() ?: return null
        return try {
            if (c.responseCode >= 400) return null
            val found = Net.body(c).bufferedReader().let { reader ->
                val buffer = CharArray(64 * 1024)
                val window = StringBuilder()
                var read = 0L
                var id: String? = null
                while (id == null && read < MAX_PAGE) {
                    val n = reader.read(buffer)
                    if (n <= 0) break
                    read += n
                    window.append(buffer, 0, n)
                    id = ID.find(window)?.groupValues?.get(1)
                    // Keep only a tail as overlap, so an id split across two reads is still seen.
                    if (id == null && window.length > OVERLAP) window.delete(0, window.length - OVERLAP)
                }
                id
            }
            found
        } catch (_: Exception) {
            null
        } finally {
            // disconnect, not close: there is no point in draining the two megabytes left.
            runCatching { c.disconnect() }
        }
    }

    /** The three places a channel page names its own id, whichever comes first. */
    private val ID = Regex("(?:feeds/videos\\.xml\\?channel_id=|/channel/|\"externalId\":\"|\"channelId\":\")(UC[\\w-]{20,})")
    private const val OVERLAP = 512
    private const val MAX_PAGE = 6L * 1024 * 1024
}
