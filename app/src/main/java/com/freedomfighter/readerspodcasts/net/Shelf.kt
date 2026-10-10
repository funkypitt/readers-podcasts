package com.freedomfighter.readerspodcasts.net

import com.freedomfighter.readerspodcasts.App
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Epub
import com.freedomfighter.readerspodcasts.data.Settings
import com.freedomfighter.readerspodcasts.data.Transcripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The transcripts, sent to the library of Reader's Books: a folder "transcriptions" at the top of
 * the drive the books are on, one folder per channel in it, one small book per episode — and a
 * second one, named after its language, for a translation. Reader's Books lists them like any
 * other book, and they can be read, highlighted and commented there.
 *
 * Sent as soon as a transcript is made; what could not be sent (no network, a login refused) is
 * sent the next time the app starts or another transcript is made. Nothing already sent is ever
 * written again: a highlight made in Reader's Books is a place in that very text.
 */
object Shelf {
    const val FOLDER = "transcriptions"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    @Volatile private var running = false
    @Volatile private var again = false

    class Refused : IOException("wrong login")

    fun configured(s: Settings) = s.shelfUrl.isNotBlank() && s.shelfUsername.isNotBlank() && s.shelfPassword.isNotEmpty()

    /** What of an episode is on the drive: "" nothing, "t" its transcript, "t+fr" its translation into French as well. */
    fun wanted(e: Episode): String = if (!e.transcript) "" else if (e.translation.isBlank()) "t" else "t+" + e.translation

    fun pending(app: App): List<Episode> = app.store.episodes.value.filter { it.transcript && it.sent != wanted(it) }

    /** Everything made and not yet on the drive, in the background; `after` hears how it went ("" or the reason). */
    fun send(app: App, after: ((String) -> Unit)? = null) {
        val s = app.prefs.settings.value
        if (!configured(s)) return
        if (running) { again = true; return }
        running = true
        scope.launch {
            var failure = ""
            try {
                for (e in pending(app)) {
                    try { one(app, s, e) }
                    catch (x: Refused) { failure = "login"; break }
                    catch (x: Exception) { failure = x.message ?: x.javaClass.simpleName }
                }
            } finally {
                running = false
                app.prefs.setShelfError(failure)
                after?.let { tell -> launch(Dispatchers.Main) { tell(failure) } }
                if (again) { again = false; send(app) }
            }
        }
    }

    private fun enc(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun check(code: Int) {
        if (code == 401 || code == 403) throw Refused()
        if (code !in 200..299) throw IOException("HTTP $code")
    }

    private fun folder(url: String, auth: String) {
        http.newCall(Request.Builder().url(url).method("MKCOL", null).header("Authorization", auth).build()).execute()
            .use { if (it.code != 201 && it.code != 405 && it.code != 301) check(it.code) }     // made, or there already
    }

    private fun put(url: String, auth: String, body: ByteArray) {
        http.newCall(Request.Builder().url(url).put(body.toRequestBody("application/epub+zip".toMediaType())).header("Authorization", auth).build())
            .execute().use { check(it.code) }
    }

    /** One episode: its transcript if it is not there yet, then its translation. Blocking. */
    private fun one(app: App, s: Settings, e: Episode) {
        val auth = Credentials.basic(s.shelfUsername.trim(), s.shelfPassword, Charsets.UTF_8)
        val channel = app.store.feed(e.feedId)?.title?.ifBlank { null } ?: "podcasts"
        val base = s.shelfUrl.trim().trimEnd('/') + "/" + enc(FOLDER) + "/"
        val place = base + enc(Epub.fileName(channel)) + "/"
        val name = Epub.fileName(e.title)
        val source = listOf(channel, if (e.published > 0) SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(e.published)) else "")
            .filter { it.isNotBlank() }.joinToString(" · ")
        var made = false
        fun folders() { if (!made) { folder(base, auth); folder(place, auth); made = true } }
        if (!e.sent.startsWith("t")) {
            val (language, lines) = Transcripts.load(app, e.id) ?: return
            folders()
            put(place + enc("$name.epub"), auth, Epub.build(e.id, e.title, channel, language, source, Transcripts.asText(lines)))
            app.store.updateEpisode(e.id) { it.copy(sent = "t") }
        }
        if (e.translation.isNotBlank() && e.sent != wanted(e)) {
            val lines = Transcripts.loadTranslation(app, e.id, e.translation) ?: return
            folders()
            put(place + enc("$name · ${e.translation}.epub"), auth,
                Epub.build(e.id + "." + e.translation, "${e.title} · ${e.translation}", channel, e.translation, source, Transcripts.asText(lines)))
            app.store.updateEpisode(e.id) { it.copy(sent = wanted(it)) }
        }
    }
}
