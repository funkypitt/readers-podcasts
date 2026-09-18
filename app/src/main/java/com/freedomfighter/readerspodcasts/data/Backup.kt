package com.freedomfighter.readerspodcasts.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * `reglages.json` — the settings, the subscriptions and where each episode was left, in one
 * file. With OPML it is the whole of what moves between the phone and the desktop: there is no
 * server, so these two files *are* the synchronisation, and both are readable by hand.
 *
 * Episode ids are derived from the feed url and the guid, so the desktop computes the same id
 * for the same episode and a position carries across without either side knowing the other.
 */
object Backup {
    const val VERSION = 1

    fun export(settings: Map<String, Any>, feeds: List<Feed>, episodes: List<Episode>): String {
        val root = JSONObject()
        root.put("app", "readers-podcasts")
        root.put("version", VERSION)
        root.put("exported", System.currentTimeMillis())
        root.put("settings", JSONObject(settings))
        root.put("feeds", JSONArray().apply {
            feeds.forEach { f ->
                put(JSONObject().apply {
                    put("url", f.url); put("title", f.title); put("kind", f.kind.name)
                    put("autoDownload", f.autoDownload); put("keepCount", f.keepCount)
                })
            }
        })
        // Only what the other side cannot work out for itself: a fresh episode in its original
        // state would say nothing, and would make the file grow for no reason.
        root.put("episodes", JSONArray().apply {
            episodes.filter { it.state != State.NEW || it.positionMs > 0 || it.starred }.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id); put("feed", e.feedId); put("title", e.title)
                    put("positionMs", e.positionMs); put("state", e.state.name); put("lastPlayed", e.lastPlayed)
                    put("starred", e.starred)
                })
            }
        })
        return root.toString(2)
    }

    data class Restored(val settings: Map<String, Any?>, val feeds: List<Feed>, val states: List<EpisodeState>)
    data class EpisodeState(val id: String, val positionMs: Long, val state: State, val lastPlayed: Long, val starred: Boolean = false)

    fun parse(text: String): Restored {
        val root = JSONObject(text)
        val settings = root.optJSONObject("settings")?.let { o ->
            o.keys().asSequence().associateWith { k -> o.get(k) }
        } ?: emptyMap()
        val feeds = root.optJSONArray("feeds")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val url = o.optString("url").trim().ifBlank { return@mapNotNull null }
                Feed(
                    id = feedId(url), url = url, title = o.optString("title").ifBlank { url },
                    kind = runCatching { Kind.valueOf(o.optString("kind", "RSS")) }.getOrDefault(Kind.RSS),
                    autoDownload = o.optBoolean("autoDownload"), keepCount = o.optInt("keepCount", 50),
                )
            }
        } ?: emptyList()
        val states = root.optJSONArray("episodes")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                EpisodeState(
                    id, o.optLong("positionMs"),
                    runCatching { State.valueOf(o.optString("state", "NEW")) }.getOrDefault(State.NEW),
                    o.optLong("lastPlayed"), o.optBoolean("starred"),
                )
            }
        } ?: emptyList()
        return Restored(settings, feeds, states)
    }
}
