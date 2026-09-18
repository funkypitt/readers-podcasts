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
        val response: YoutubeDLResponse? = runCatching {
            YoutubeDL.getInstance().execute(request, episode.id) { progress, _, _ ->
                onProgress(progress.toInt().coerceIn(0, 100))
            }
        }.getOrElse { e ->
            // Stopping a download destroys the process, and that comes back here as a failure:
            // it is not one, and nothing should be said about it.
            if (cancelled()) return null
            throw e
        }
        if (cancelled()) { leftovers(directory, episode.id).forEach { it.delete() }; return null }
        val file = leftovers(directory, episode.id).firstOrNull { !it.name.endsWith(".part") }
        return file ?: throw IllegalStateException(why(response))
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    /**
     * yt-dlp itself, brought up to date. YouTube changes and yt-dlp follows within days, so the
     * app must not have to be rebuilt each time for a download to work again.
     */
    fun update(context: Context): String =
        YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)?.name
            ?: "NOTHING"

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
        val err = response?.err?.trim()?.lines()?.lastOrNull { it.isNotBlank() }
        val out = response?.out?.trim()?.lines()?.lastOrNull { it.isNotBlank() }
        return (err ?: out ?: "yt-dlp: rien à lire").take(160)
    }
}
