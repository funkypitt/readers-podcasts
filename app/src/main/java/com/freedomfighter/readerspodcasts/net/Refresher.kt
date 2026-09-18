package com.freedomfighter.readerspodcasts.net

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.freedomfighter.readerspodcasts.BuildConfig
import com.freedomfighter.readerspodcasts.R
import com.freedomfighter.readerspodcasts.data.Feed
import com.freedomfighter.readerspodcasts.data.FeedParser
import com.freedomfighter.readerspodcasts.data.Kind
import com.freedomfighter.readerspodcasts.data.State
import com.freedomfighter.readerspodcasts.data.Store
import com.freedomfighter.readerspodcasts.data.feedId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetching subscriptions: adding one, and bringing them all up to date. */
object Refresher {

    /** What the screens show while feeds are being fetched. */
    object Live {
        /** How many are left to fetch, and how many there were: the screen counts them off. */
        var running by mutableIntStateOf(0)
        var total by mutableIntStateOf(0)
        var error by mutableStateOf("")
    }

    class Refused(val reasonRes: Int) : Exception()

    /**
     * Add a subscription from whatever the user pasted. A YouTube address is recognised here
     * even in the public build, so that it can be refused in so many words rather than fetched
     * and found empty.
     */
    suspend fun add(context: Context, store: Store, raw: String): Result<Feed> = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalise(raw)
            if (Youtube.looksLikeYoutube(url)) {
                if (!BuildConfig.YOUTUBE) throw Refused(R.string.youtube_not_here)
                val feedUrl = Youtube.channelFeed(url) ?: throw Refused(R.string.youtube_no_channel)
                return@runCatching subscribe(store, feedUrl, Kind.YOUTUBE)
            }
            subscribe(store, url, Kind.RSS)
        }
    }

    private fun subscribe(store: Store, url: String, kind: Kind): Feed {
        val id = feedId(url)
        store.feed(id)?.let { return it }
        val parsed = fetch(url, id, kind)
        val feed = Feed(
            id = id, url = url, title = parsed.title.ifBlank { hostOf(url) }, author = parsed.author,
            kind = kind, lastFetch = System.currentTimeMillis(),
        )
        store.addFeed(feed)
        store.merge(id, parsed.episodes)
        return feed
    }

    /** One feed, brought up to date. The error, if any, is kept on the feed and shown on its row. */
    suspend fun refresh(context: Context, store: Store, feed: Feed): Boolean = withContext(Dispatchers.IO) {
        try {
            val parsed = fetch(feed.url, feed.id, feed.kind)
            store.merge(feed.id, parsed.episodes)
            store.updateFeed(feed.id) {
                it.copy(
                    title = if (it.title.isBlank() || it.title == it.url || it.title == hostOf(it.url)) parsed.title.ifBlank { it.title } else it.title,
                    author = parsed.author.ifBlank { it.author },
                    lastFetch = System.currentTimeMillis(), lastError = "",
                )
            }
            if (feed.autoDownload) queueNew(context, store, feed.id)
            true
        } catch (e: Exception) {
            store.updateFeed(feed.id) { it.copy(lastError = (e.message ?: e.javaClass.simpleName).take(120)) }
            false
        }
    }

    /** Every subscription, one after the other: a phone has one connection, and feeds are small. */
    suspend fun refreshAll(context: Context, store: Store) {
        if (Live.running > 0) return
        val feeds = store.feeds.value
        if (feeds.isEmpty()) return
        if (!Net.online(context)) { Live.error = context.getString(R.string.offline); return }
        Live.error = ""
        Live.total = feeds.size
        Live.running = feeds.size
        try {
            feeds.forEach { f ->
                refresh(context, store, f)
                Live.running -= 1
            }
        } finally {
            Live.running = 0
        }
    }

    /** After a refresh of a feed set to download by itself: whatever is new and not yet here. */
    private fun queueNew(context: Context, store: Store, feedId: String) {
        store.episodesOf(feedId)
            .filter { it.state == State.NEW && !it.downloaded }
            .take(3)
            .forEach { DownloadService.start(context, it.id) }
    }

    private fun fetch(url: String, id: String, kind: Kind): FeedParser.Parsed {
        val c = Net.open(url)
        try {
            if (c.responseCode >= 400) throw IllegalStateException("HTTP ${c.responseCode}")
            return Net.body(c).use { FeedParser.parse(id, kind, it) }
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /**
     * What to call a feed that does not name itself — and there are such feeds, with an empty
     * `<title/>`. Its host reads in a list; its whole address does not.
     */
    fun hostOf(url: String): String =
        runCatching { java.net.URL(url).host.removePrefix("www.") }.getOrNull()?.ifBlank { null } ?: url

    /** `example.com/feed`, `feed://…`, a pasted line with spaces around it — all meant as https. */
    fun normalise(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("feed://", true)) s = "https://" + s.removeRange(0, 7)
        if (s.startsWith("podcast://", true)) s = "https://" + s.removeRange(0, 10)
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        return s
    }
}
