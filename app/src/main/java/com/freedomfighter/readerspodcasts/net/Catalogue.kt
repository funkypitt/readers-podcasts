package com.freedomfighter.readerspodcasts.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Finding a podcast by its name, in the two directories anyone may ask without a key: Apple's
 * (the largest, and the one most other players use) and fyyd (independent, strong on European
 * podcasts). Both are asked at once; either may fail without the other's answer being lost.
 * Nothing is kept: the feed address found here goes down the same road as one pasted by hand.
 */
object Catalogue {
    const val APPLE = "Apple Podcasts"
    const val FYYD = "fyyd"

    data class Found(val title: String, val author: String, val url: String, val episodes: Int = 0)

    /** What came back: the podcasts, and the directories that did not answer. */
    data class Outcome(val found: List<Found>, val failed: List<String>)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The store of the reader's country answers first with that country's podcasts. */
    fun appleUrl(term: String, country: String = "", limit: Int = 30): String =
        "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=$limit&term=${enc(term)}" +
            if (country.length == 2) "&country=${country.lowercase()}" else ""

    /** `term=` rather than `title=`: the latter finds nothing as soon as there are two words. */
    fun fyydUrl(term: String, count: Int = 30): String =
        "https://api.fyyd.de/0.2/search/podcast?count=$count&term=${enc(term)}"

    fun parseApple(json: String): List<Found> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("feedUrl").trim()
            val title = o.optString("collectionName").trim().ifBlank { o.optString("trackName").trim() }
            if (url.isBlank() || title.isBlank()) null
            else Found(title, author(o.optString("artistName")), url, o.optInt("trackCount"))
        }
    }

    fun parseFyyd(json: String): List<Found> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("xmlURL").trim()
            val title = o.optString("title").trim()
            if (url.isBlank() || title.isBlank()) null
            else Found(title, author(o.optString("author")), url, o.optInt("episode_count"))
        }
    }

    /** Some feeds put their licence where the author goes; a line of that is no name. */
    fun author(raw: String): String {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        return if (s.length > 80 || s.contains("://")) "" else s
    }

    /**
     * The same feed as both directories spell it: `http://` and `https://`, `www.`, the case of
     * the host, a trailing slash — `https://AV.dharmaseed.org/feeds/recordings/` and
     * `http://av.dharmaseed.org/feeds/recordings` are one podcast.
     */
    fun key(url: String): String {
        var s = url.trim().replace(Regex("^[a-zA-Z]+://"), "").removeSuffix("/")
        val slash = s.indexOf('/').let { if (it < 0) s.length else it }
        s = s.substring(0, slash).lowercase().removePrefix("www.") + s.substring(slash)
        return s
    }

    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * One list: taken in turn from each directory, so that neither buries the other; a feed
     * both know appears once, with whatever either knew of it. Then the names that hold the
     * whole query come first, those holding all its words next, the rest after — each group in
     * the order the directories gave.
     */
    fun merge(query: String, vararg lists: List<Found>): List<Found> {
        val seen = LinkedHashMap<String, Found>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (list in lists) {
            val f = list.getOrNull(i) ?: continue
            val k = key(f.url)
            val had = seen[k]
            seen[k] = if (had == null) f else had.copy(
                author = had.author.ifBlank { f.author },
                episodes = maxOf(had.episodes, f.episodes),
            )
        }
        val q = fold(query.trim())
        val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        return seen.values.sortedBy { f ->
            val t = fold(f.title)
            when {
                q.isNotBlank() && t.contains(q) -> 0
                words.isNotEmpty() && words.all { t.contains(it) } -> 1
                else -> 2
            }
        }
    }

    private fun get(url: String): String {
        val c = Net.open(url)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return Net.body(c).bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Both directories at once, off the main thread. */
    suspend fun search(term: String, country: String = ""): Outcome = withContext(Dispatchers.IO) {
        coroutineScope {
            val apple = async {
                runCatching {
                    // A country Apple does not know is a 400; the store of the United States then.
                    val text = runCatching { get(appleUrl(term, country)) }
                        .recoverCatching { if (country.isNotBlank()) get(appleUrl(term)) else throw it }
                        .getOrThrow()
                    parseApple(text)
                }
            }
            val fyyd = async { runCatching { parseFyyd(get(fyydUrl(term))) } }
            val a = apple.await()
            val f = fyyd.await()
            Outcome(
                merge(term, a.getOrDefault(emptyList()), f.getOrDefault(emptyList())),
                listOfNotNull(APPLE.takeIf { a.isFailure }, FYYD.takeIf { f.isFailure }),
            )
        }
    }
}
