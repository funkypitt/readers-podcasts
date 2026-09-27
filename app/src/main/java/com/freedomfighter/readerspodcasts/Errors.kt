package com.freedomfighter.readerspodcasts

import android.content.Context
import androidx.annotation.StringRes
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A sentence already in the user's language, shown as it is. */
class Shown(message: String) : IllegalStateException(message)

/** A server refused with this status. */
class HttpStatus(val code: Int) : java.io.IOException("HTTP $code")

/** An episode's download ended before the length the server announced. */
class CutShort : IllegalStateException("download cut short")

/** The system would not create the exported .txt under this name. */
class CannotCreate(val name: String) : IllegalStateException("cannot create $name")

/**
 * A failure put into the user's language. What this app and the speech module throw is known and
 * gets a sentence of its own; the speech module's messages are English and are recognised here,
 * on the app side, rather than changed there. Anything else is someone else's text — the system's,
 * yt-dlp's — which cannot be translated: it follows [lead], a phrase that says what failed.
 */
object Errors {
    fun describe(ctx: Context, e: Throwable, @StringRes lead: Int): String =
        known(ctx, e) ?: ctx.getString(lead, e.message?.trim().orEmpty().ifBlank { e.javaClass.simpleName })

    /** The sentence for a failure this app knows, or null for anyone else's. */
    fun known(ctx: Context, e: Throwable): String? {
        val m = e.message.orEmpty()
        return when {
            e is Shown -> m
            e is HttpStatus -> ctx.getString(R.string.error_server_http, e.code)
            e is CutShort -> ctx.getString(R.string.error_download_cut)
            e is CannotCreate -> ctx.getString(R.string.error_cannot_create, e.name)
            e is UnknownHostException || e is ConnectException || e is SocketTimeoutException -> ctx.getString(R.string.offline)
            // from readers-speech (share/Download.kt, translate/TranslateModel.kt, audio/Pcm.kt)
            DOWNLOAD_HTTP.find(m) != null -> ctx.getString(R.string.error_download_http, DOWNLOAD_HTTP.find(m)!!.groupValues[1].toInt())
            m == "download cut short" -> ctx.getString(R.string.error_download_cut)
            NO_SPACE.find(m) != null -> ctx.getString(R.string.error_no_space, NO_SPACE.find(m)!!.groupValues[1].toLong())
            m == "no audio track" -> ctx.getString(R.string.error_no_audio)
            else -> null
        }
    }

    private val DOWNLOAD_HTTP = Regex("^download: HTTP (\\d+)")
    private val NO_SPACE = Regex("^not enough space: (\\d+) MB needed")
}
