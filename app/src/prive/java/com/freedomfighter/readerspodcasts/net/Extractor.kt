package com.freedomfighter.readerspodcasts.net

import android.content.Context
import com.freedomfighter.readerspodcasts.data.Episode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File

/**
 * Turning a video page into an audio file, with yt-dlp.
 *
 * A YouTube entry carries no media of its own — only the address of a page — so where an
 * ordinary episode is fetched over HTTP, this one is handed to yt-dlp, which finds the audio
 * track and brings that down alone. No conversion, and so no ffmpeg: what YouTube already
 * serves as m4a or opus is what Media3 plays.
 *
 * This file exists in the private build only. The public one has a stub of the same shape in
 * `src/publique`, so nothing elsewhere in the app has to know which build it is running in.
 */
object Extractor {
    const val AVAILABLE = true

    @Volatile private var ready = false

    /**
     * Unpacks Python and yt-dlp into the app's files. Seconds, the first time and after every
     * update of the app, which is why the caller says so on screen before waiting for it.
     */
    @Synchronized
    fun prepare(context: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(context.applicationContext)
        ready = true
    }

    private const val TAG = "ReadersPodcasts"

    /**
     * The audio of one page, into [directory]. The file is named after the episode's id, with
     * whatever extension the chosen track turns out to have; the finished file is returned, or
     * null if it was stopped on the way.
     */
    fun fetch(
        context: Context,
        episode: Episode,
        directory: File,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
        /** Called when the download has to stop and fetch a newer yt-dlp first. */
        onUpdating: () -> Unit = {},
    ): File? {
        prepare(context)
        leftovers(directory, episode.id).forEach { it.delete() }
        val request = YoutubeDLRequest(episode.mediaUrl).apply {
            // The best audio-only track, preferring the one that needs no remuxing.
            addOption("-f", "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio")
            addOption("-o", File(directory, episode.id).absolutePath + ".%(ext)s")
            // A link often carries a playlist as well; only the video asked for is wanted.
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--retries", "3")
        }
        val response: YoutubeDLResponse? = runCatching { run(request, episode.id, onProgress) }.getOrElse { first ->
            if (cancelled()) return null
            // YouTube answers an old yt-dlp with 403, and says so in a warning nobody reads.
            // Rather than leave that for the next weekly update, it is fetched here and the
            // download tried once more — which is the whole difference between an app that
            // works this morning and one that does not.
            if (!stale(first)) throw IllegalStateException(tail(first.message) ?: first.javaClass.simpleName, first)
            android.util.Log.w(TAG, "yt-dlp refusé (403) : mise à jour puis nouvel essai")
            onUpdating()
            runCatching { update(context) }
            if (cancelled()) return null
            runCatching { run(request, episode.id, onProgress) }.getOrElse { second ->
                if (cancelled()) return null
                throw IllegalStateException(tail(second.message) ?: second.javaClass.simpleName, second)
            }
        }
        if (cancelled()) { leftovers(directory, episode.id).forEach { it.delete() }; return null }
        val file = leftovers(directory, episode.id).firstOrNull { !it.name.endsWith(".part") }
        return file ?: throw IllegalStateException(why(response))
    }

    /** One video of a channel, as a flat listing gives it: enough to make a row of it. */
    data class Listed(val videoId: String, val title: String, val durationMs: Long)

    /**
     * The videos of a channel beyond the fifteen its feed carries, [from] (1-based) to [from] +
     * [count] − 1, newest first.
     *
     * YouTube's Atom feed is a window on the latest fifteen, not a catalogue: everything older is
     * simply not in it. yt-dlp can walk the channel's own page instead, and a flat listing costs
     * one request for a whole page of videos rather than one per video.
     *
     * No dates: a flat listing does not carry them, and yt-dlp's approximate ones came back
     * identical for every video, which is worse than none. What is known is the order, which the
     * caller keeps; the row then shows the length instead of a date rather than a date that lies.
     */
    fun listChannel(context: Context, feedUrl: String, from: Int, count: Int): List<Listed> {
        // No weekly freshening here: that belongs to the download service. A yt-dlp too old for
        // YouTube is caught by [attempt], which fetches a new one and tries again at once.
        prepare(context)
        val page = pageOf(feedUrl) ?: throw IllegalStateException("cette chaîne n'a pas de page à lire")
        val request = YoutubeDLRequest(page).apply {
            addOption("--flat-playlist")
            addOption("-J")
            addOption("--playlist-start", from.toString())
            addOption("--playlist-end", (from + count - 1).toString())
            addOption("--no-warnings")
        }
        val response = attempt(context) { YoutubeDL.getInstance().execute(request, "list-$page") }
        val text = response.out.orEmpty().trim()
        if (text.isEmpty()) throw IllegalStateException(tail(response.err) ?: "yt-dlp : rien à lire")
        val entries = org.json.JSONObject(text).optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val o = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Listed(id, o.optString("title").ifBlank { id }, (o.optDouble("duration", 0.0) * 1000).toLong())
        }
    }

    /** The page that lists a channel's videos, from the address of its feed. */
    private fun pageOf(feedUrl: String): String? {
        Regex("channel_id=([\\w-]+)").find(feedUrl)?.let { return "https://www.youtube.com/channel/${it.groupValues[1]}/videos" }
        Regex("playlist_id=([\\w-]+)").find(feedUrl)?.let { return "https://www.youtube.com/playlist?list=${it.groupValues[1]}" }
        return null
    }

    /** One attempt, and a second after an update when YouTube turned away an old yt-dlp. */
    private fun <T> attempt(context: Context, block: () -> T): T = runCatching { block() }.getOrElse { first ->
        if (!stale(first)) throw IllegalStateException(tail(first.message) ?: first.javaClass.simpleName, first)
        android.util.Log.w(TAG, "yt-dlp refusé : mise à jour puis nouvel essai")
        runCatching { update(context) }
        runCatching { block() }.getOrElse { second ->
            throw IllegalStateException(tail(second.message) ?: second.javaClass.simpleName, second)
        }
    }

    private fun run(request: YoutubeDLRequest, id: String, onProgress: (Int) -> Unit): YoutubeDLResponse =
        YoutubeDL.getInstance().execute(request, id) { progress, _, _ ->
            onProgress(progress.toInt().coerceIn(0, 100))
        }

    /** Whether what came back is YouTube turning away a yt-dlp it considers too old. */
    private fun stale(e: Throwable): Boolean {
        val text = e.message.orEmpty()
        return text.contains("403") || text.contains("Forbidden", true) ||
            text.contains("version is out of date", true) || text.contains("nsig extraction failed", true) ||
            text.contains("Sign in to confirm", true)
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    /**
     * yt-dlp itself, brought up to date. YouTube changes and yt-dlp follows within days, so the
     * app must not have to be rebuilt each time for a download to work again.
     */
    fun update(context: Context): String {
        // Without this, updating threw "not initialized" — and the caller, seeing an exception
        // it had chosen to ignore, wrote down that yt-dlp had been brought up to date. It never
        // was, and every download came back 403 Forbidden.
        prepare(context)
        return YoutubeDL.getInstance()
            .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)?.name ?: "NOTHING"
    }

    /** What version is in place, for the settings row to show rather than claim nothing. */
    fun version(context: Context): String =
        runCatching { YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull().orEmpty()

    private fun leftovers(directory: File, id: String): List<File> =
        directory.listFiles { f -> f.name.startsWith(id) }?.toList().orEmpty()

    /**
     * Why nothing came down. yt-dlp says it on its error stream, and warns about its own age on
     * the ordinary one; a line that only repeated the warning sent the reader after the wrong
     * thing, so what is shown is the last line of the error, and the warning only if there is
     * nothing else.
     */
    private fun why(response: YoutubeDLResponse?): String {
        val err = tail(response?.err)
        val out = tail(response?.out)
        return (err ?: out ?: "yt-dlp: rien à lire")
    }

    /**
     * The last few lines, not just the last one: yt-dlp says what happened over two or three
     * lines and warns about its own age on a fourth, so a single line often named the least
     * useful of them.
     */
    private fun tail(text: String?): String? = text?.trim()?.lines()
        ?.filter { it.isNotBlank() && !it.startsWith("[download]") }
        ?.takeLast(3)?.joinToString("\n")?.takeIf { it.isNotBlank() }?.take(400)
}
