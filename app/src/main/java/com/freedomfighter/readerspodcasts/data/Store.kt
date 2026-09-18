package com.freedomfighter.readerspodcasts.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the app knows, as plain JSON: `feeds.json` for the subscriptions and one
 * `feeds/<id>.json` per subscription for its episodes.
 *
 * One file per feed rather than a single index: refreshing a feed rewrites only that feed, and
 * a subscription with hundreds of episodes never makes saving a listening position — which
 * happens every few seconds while playing — rewrite everything else.
 */
class Store(private val context: Context) {
    private val index = File(context.filesDir, "feeds.json")
    private val dir = File(context.filesDir, "feeds").apply { mkdirs() }

    private val _feeds = MutableStateFlow(loadFeeds())
    val feeds: StateFlow<List<Feed>> = _feeds

    private val _episodes = MutableStateFlow(_feeds.value.flatMap { loadEpisodes(it.id) })
    val episodes: StateFlow<List<Episode>> = _episodes

    var onChange: (() -> Unit)? = null

    /** Where downloaded audio lives: app-private, no permission, and roomy enough for podcasts. */
    fun audioDir(): File =
        (context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS) ?: File(context.filesDir, "audio")).apply { mkdirs() }

    // ---- reading ----

    fun feed(id: String): Feed? = _feeds.value.firstOrNull { it.id == id }
    fun episode(id: String): Episode? = _episodes.value.firstOrNull { it.id == id }
    fun episodesOf(feedId: String): List<Episode> = _episodes.value.filter { it.feedId == feedId }.sortedByDescending { it.published }

    /** « Épisodes » — everything there is, the latest first, across all the channels. */
    fun recent(): List<Episode> = _episodes.value.sortedByDescending { it.published }

    /** « Favoris » — the episodes given a star, the latest first. */
    fun favourites(): List<Episode> = _episodes.value.filter { it.starred }.sortedByDescending { it.published }

    /**
     * « Chaînes » — the subscriptions, the one that published last at the top. A hundred and
     * forty channels in alphabetical order says nothing; in this order the first screen is the
     * news.
     */
    fun channels(): List<Feed> = channelsByLatest(_feeds.value, _episodes.value)

    fun unplayedCount(feedId: String): Int = _episodes.value.count { it.feedId == feedId && it.state != State.PLAYED }

    /** The episode to offer when playback resumes with nothing loaded. */
    fun last(): Episode? = _episodes.value.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }

    // ---- writing ----

    @Synchronized fun addFeed(feed: Feed): Feed {
        val existing = _feeds.value.firstOrNull { it.id == feed.id }
        if (existing != null) return existing
        _feeds.value = _feeds.value + feed
        saveFeeds()
        changed()
        return feed
    }

    @Synchronized fun updateFeed(id: String, edit: (Feed) -> Feed) {
        val list = _feeds.value.map { if (it.id == id) edit(it) else it }
        if (list == _feeds.value) return
        _feeds.value = list
        saveFeeds()
        changed()
    }

    /** Unsubscribing takes the episodes and their files with it: nothing is left behind. */
    @Synchronized fun removeFeed(id: String) {
        _episodes.value.filter { it.feedId == id }.forEach { runCatching { File(it.localPath).delete() } }
        _episodes.value = _episodes.value.filterNot { it.feedId == id }
        _feeds.value = _feeds.value.filterNot { it.id == id }
        File(dir, "$id.json").delete()
        saveFeeds()
        changed()
    }

    @Synchronized fun updateEpisode(id: String, edit: (Episode) -> Episode) {
        val before = _episodes.value.firstOrNull { it.id == id } ?: return
        val after = edit(before)
        if (after == before) return
        _episodes.value = _episodes.value.map { if (it.id == id) after else it }
        saveEpisodes(after.feedId)
        changed()
    }

    /** Forget the downloaded file, keeping the episode and where it was left. */
    @Synchronized fun deleteFile(id: String) {
        val e = _episodes.value.firstOrNull { it.id == id } ?: return
        if (e.downloaded) runCatching { File(e.localPath).delete() }
        updateEpisode(id) { it.copy(localPath = "") }
    }

    /**
     * Fold what a refresh brought into what we already had. An episode we know keeps everything
     * that is ours — where it was left, its file, whether it was heard — and takes from the feed
     * only what the feed owns: its title, its date, its media. Publishers do edit their items.
     */
    @Synchronized fun merge(feedId: String, fresh: List<Episode>) {
        val keep = _feeds.value.firstOrNull { it.id == feedId }?.keepCount ?: 50
        val mine = _episodes.value.filter { it.feedId == feedId }.associateBy { it.id }
        val merged = fresh.map { new ->
            val old = mine[new.id] ?: return@map new
            new.copy(
                localPath = old.localPath, positionMs = old.positionMs, state = old.state,
                lastPlayed = old.lastPlayed, starred = old.starred,
                durationMs = if (new.durationMs > 0) new.durationMs else old.durationMs,
            )
        }
        // Episodes the feed no longer lists are dropped, unless they are on the phone or begun:
        // a feed that only publishes its last ten items must not delete what one is listening to.
        val fresIds = merged.map { it.id }.toSet()
        val orphans = mine.values.filter { it.id !in fresIds && (it.downloaded || it.state == State.STARTED || it.starred) }
        val all = (merged + orphans).sortedByDescending { it.published }
        // Trimming keeps the newest, and never throws away a file or a begun episode.
        val trimmed = all.filterIndexed { i, e -> i < keep || e.downloaded || e.state == State.STARTED || e.starred }
            .distinctBy { it.id }
        _episodes.value = _episodes.value.filterNot { it.feedId == feedId } + trimmed
        saveEpisodes(feedId)
        changed()
    }

    private fun changed() = onChange?.invoke()

    // ---- JSON ----

    private fun loadFeeds(): List<Feed> = runCatching {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Feed(
                id = o.getString("id"), url = o.getString("url"), title = o.optString("title"),
                author = o.optString("author"),
                kind = runCatching { Kind.valueOf(o.optString("kind", "RSS")) }.getOrDefault(Kind.RSS),
                addedAt = o.optLong("addedAt"), lastFetch = o.optLong("lastFetch"),
                autoDownload = o.optBoolean("autoDownload"), keepCount = o.optInt("keepCount", 50),
                lastError = o.optString("lastError"),
            )
        }
    }.getOrDefault(emptyList())

    private fun saveFeeds() {
        val arr = JSONArray()
        _feeds.value.forEach { f ->
            arr.put(JSONObject().apply {
                put("id", f.id); put("url", f.url); put("title", f.title); put("author", f.author)
                put("kind", f.kind.name); put("addedAt", f.addedAt); put("lastFetch", f.lastFetch)
                put("autoDownload", f.autoDownload); put("keepCount", f.keepCount); put("lastError", f.lastError)
            })
        }
        runCatching { index.writeText(arr.toString()) }
    }

    private fun loadEpisodes(feedId: String): List<Episode> = runCatching {
        val arr = JSONArray(File(dir, "$feedId.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Episode(
                id = o.getString("id"), feedId = feedId, title = o.optString("title"),
                published = o.optLong("published"), mediaUrl = o.optString("mediaUrl"),
                mime = o.optString("mime", "audio/*"), bytes = o.optLong("bytes"),
                durationMs = o.optLong("durationMs"), localPath = o.optString("localPath"),
                positionMs = o.optLong("positionMs"),
                state = runCatching { State.valueOf(o.optString("state", "NEW")) }.getOrDefault(State.NEW),
                lastPlayed = o.optLong("lastPlayed"), description = o.optString("description"),
                starred = o.optBoolean("starred"),
            )
        }
    }.getOrDefault(emptyList())
        // A file deleted from outside (a cleanup, a restore) must not leave a row claiming to play.
        .map { if (it.downloaded && !File(it.localPath).exists()) it.copy(localPath = "") else it }
        // Written by a version that let a repeated guid through: one of the two has to go, or
        // the list it belongs to cannot be drawn.
        .distinctBy { it.id }

    private fun saveEpisodes(feedId: String) {
        val arr = JSONArray()
        _episodes.value.filter { it.feedId == feedId }.sortedByDescending { it.published }.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("title", e.title); put("published", e.published); put("mediaUrl", e.mediaUrl)
                put("mime", e.mime); put("bytes", e.bytes); put("durationMs", e.durationMs); put("localPath", e.localPath)
                put("positionMs", e.positionMs); put("state", e.state.name); put("lastPlayed", e.lastPlayed)
                put("description", e.description); put("starred", e.starred)
            })
        }
        runCatching { File(dir, "$feedId.json").writeText(arr.toString()) }
    }
}
