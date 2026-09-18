package com.freedomfighter.readerspodcasts.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freedomfighter.readerspodcasts.App
import com.freedomfighter.readerspodcasts.MainActivity
import com.freedomfighter.readerspodcasts.R
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads episodes one after the other, in the foreground so that Android lets them finish,
 * with a notification that can stop them.
 *
 * What an interrupted attempt already brought down is kept in a `.part` file and asked for
 * again with a Range header: a podcast is tens of megabytes over a phone connection, and a
 * download that always starts from zero is one that never finishes.
 */
class DownloadService : Service() {

    private data class Job(val id: String, val force: Boolean)

    private val queue = ConcurrentLinkedQueue<Job>()
    private val cancelled = AtomicBoolean(false)
    private var running = false
    private var lastStartId = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val app get() = application as App

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        goForeground()
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true); queue.clear(); Live.waiting.clear()
                if (Live.id.isNotBlank()) Extractor.cancel(Live.id)
                if (!running) finish()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ONE -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id != null) {
                    queue.removeAll { it.id == id }
                    Live.waiting.remove(id)
                    // The one being fetched right now can only be stopped by stopping the loop,
                    // which then carries on with whatever else was queued. yt-dlp runs in a
                    // process of its own and has to be told separately.
                    if (Live.id == id) { cancelled.set(true); Extractor.cancel(id) }
                }
                if (!running) finish()
                return START_NOT_STICKY
            }
            else -> intent?.getStringExtra(EXTRA_ID)?.let { id ->
                if (id != Live.id && queue.none { it.id == id }) {
                    queue.add(Job(id, intent.getBooleanExtra(EXTRA_FORCE, false)))
                    Live.waiting.add(id)
                }
            }
        }
        if (!running) { running = true; cancelled.set(false); scope.launch { work(); finish() } }
        return START_NOT_STICKY
    }

    private suspend fun work() {
        val ticker = scope.launch { while (isActive) { pushNotification(); delay(1500) } }
        try {
            while (true) {
                val job = queue.poll() ?: break
                // A stop applies to the file being fetched, not to the whole queue for ever.
                cancelled.set(false)
                Live.waiting.remove(job.id)
                val episode = app.store.episode(job.id) ?: continue
                if (episode.downloaded) continue
                if (!job.force && app.prefs.settings.value.wifiOnly && !Net.unmetered(this)) {
                    Live.error = getString(R.string.wifi_only_waiting)
                    Live.errorId = job.id
                    continue
                }
                Live.id = job.id; Live.percent = 0
                if (Live.errorId == job.id) { Live.errorId = ""; Live.error = "" }
                try {
                    val file = withContext(Dispatchers.IO) { fetch(episode) }
                    if (file != null) {
                        app.store.updateEpisode(job.id) { it.copy(localPath = file.path, bytes = file.length()) }
                    }
                } catch (e: Exception) {
                    if (!cancelled.get()) {
                        Live.errorId = job.id
                        Live.error = (e.message ?: e.javaClass.simpleName).take(120)
                    }
                } finally {
                    Live.id = ""; Live.percent = 0; Live.phase = ""
                }
            }
        } finally {
            ticker.cancel()
            Live.id = ""; Live.percent = 0; Live.phase = ""; Live.waiting.clear()
            running = false
        }
    }

    /**
     * The audio of one episode. A YouTube entry points at a page rather than at a file, so it
     * goes to yt-dlp; everything else is an ordinary, resumable HTTP download.
     */
    private fun fetch(episode: Episode): File? {
        val kind = app.store.feed(episode.feedId)?.kind
        if (kind == Kind.YOUTUBE) {
            if (!Extractor.AVAILABLE) throw IllegalStateException(getString(R.string.youtube_not_here))
            Live.phase = getString(R.string.preparing_ytdlp)
            freshenYtdlp()
            return try {
                Extractor.fetch(this, episode, app.store.audioDir(), { Live.percent = it }, { cancelled.get() })
            } finally {
                Live.phase = ""
            }
        }
        return fetchOverHttp(episode)
    }

    /**
     * yt-dlp, kept current without being asked. YouTube changes and yt-dlp follows within days;
     * an app that waited to be told would simply stop working one morning, with an error about
     * a version nobody had thought about. Once a week, and a failure here is not one: the
     * download is attempted all the same.
     */
    private fun freshenYtdlp() {
        val last = app.prefs.settings.value.ytdlpUpdated
        if (System.currentTimeMillis() - last < 7 * 24 * 60 * 60 * 1000L) return
        Live.phase = getString(R.string.updating_ytdlp)
        runCatching { Extractor.update(this) }
        app.prefs.setYtdlpUpdated(System.currentTimeMillis())
        Live.phase = getString(R.string.preparing_ytdlp)
    }

    /** Returns the finished file, or null if it was stopped on the way. */
    private fun fetchOverHttp(episode: Episode): File? {
        val target = File(app.store.audioDir(), fileName(episode))
        val part = File(target.parentFile, target.name + ".part")
        var have = if (part.exists()) part.length() else 0L
        val c = Net.open(episode.mediaUrl, range = have)
        try {
            if (c.responseCode >= 400) throw IllegalStateException("HTTP ${c.responseCode}")
            // 206: the server picks up where we stopped. Anything else means it sent the whole
            // file again, so what is on disk is worthless and the part starts over.
            val resuming = c.responseCode == 206
            if (!resuming) have = 0L
            val total = c.contentLengthLong.let { if (it > 0) it + have else episode.bytes }
            var done = have
            Net.body(c).use { input ->
                FileOutputStream(part, resuming).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var last = -1
                    while (true) {
                        if (cancelled.get()) return null
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != last) { last = pct; Live.percent = pct }
                    }
                }
            }
            if (total > 0 && done < total) throw IllegalStateException("download cut short")
            if (!part.renameTo(target)) { target.delete(); part.renameTo(target) }
            return target
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /** The id names the file, so nothing collides and a stale file is always recognisable. */
    private fun fileName(e: Episode): String {
        val fromUrl = e.mediaUrl.substringBefore('?').substringAfterLast('.').lowercase()
        val ext = when {
            fromUrl.length in 2..4 && fromUrl.all { it.isLetterOrDigit() } -> fromUrl
            e.mime.contains("mpeg") -> "mp3"
            e.mime.contains("mp4") || e.mime.contains("m4a") -> "m4a"
            e.mime.contains("ogg") || e.mime.contains("opus") -> "opus"
            else -> "audio"
        }
        return "${e.id}.$ext"
    }

    private fun finish() {
        if (running) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun goForeground() = startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun pushNotification() =
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); enableVibration(false) }
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = app.store.episode(Live.id)?.title ?: getString(R.string.app_name)
        val waiting = Live.waiting.size
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(
                Live.phase.ifBlank {
                    if (waiting > 0) getString(R.string.downloading_with_queue, Live.percent, waiting)
                    else getString(R.string.downloading, Live.percent)
                }
            )
            .setSmallIcon(R.drawable.ic_note)
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
            .setProgress(100, Live.percent, Live.id.isEmpty())
            .addAction(0, getString(R.string.stop), cancel)
            .build()
    }

    /** What the rows show about the downloads. */
    object Live {
        var id by mutableStateOf("")
        var percent by mutableIntStateOf(0)
        /** What is going on before the percentages start, or "": unpacking yt-dlp, mostly. */
        var phase by mutableStateOf("")
        var error by mutableStateOf("")
        var errorId by mutableStateOf("")
        val waiting = mutableStateListOf<String>()

        fun busy(which: String) = which == id || which in waiting
    }

    companion object {
        const val ACTION_CANCEL = "com.freedomfighter.readerspodcasts.DOWNLOAD_CANCEL"
        const val ACTION_CANCEL_ONE = "com.freedomfighter.readerspodcasts.DOWNLOAD_CANCEL_ONE"
        const val EXTRA_ID = "id"
        const val EXTRA_FORCE = "force"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 8

        /** [force] means the user asked for this one by name: metered or not, it is fetched. */
        fun start(ctx: Context, id: String, force: Boolean = false) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, DownloadService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_FORCE, force)
        )

        fun cancel(ctx: Context, id: String) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL_ONE).putExtra(EXTRA_ID, id)
        )
    }
}
