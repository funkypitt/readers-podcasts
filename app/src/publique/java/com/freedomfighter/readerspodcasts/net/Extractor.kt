package com.freedomfighter.readerspodcasts.net

import android.content.Context
import com.freedomfighter.readerspodcasts.data.Episode
import java.io.File

/**
 * Turning a video page into an audio file — not in this build.
 *
 * The public app carries no part of yt-dlp: not the code, not the Python it runs on. The real
 * one lives in `src/prive`, and the two are the same object with the same shape, so nothing
 * elsewhere has to ask which build it is in.
 */
object Extractor {
    /** Whether this build can do it at all. */
    const val AVAILABLE = false

    /** Unpacks what is needed, once. Slow the first time, hence never on the main thread. */
    fun prepare(context: Context) = Unit

    fun fetch(
        context: Context,
        episode: Episode,
        directory: File,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): File? = null

    fun cancel(id: String) = Unit

    /** Brings yt-dlp itself up to date; returns what to show for it. */
    fun update(context: Context): String = ""

    fun version(context: Context): String = ""
}
