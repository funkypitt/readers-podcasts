package com.freedomfighter.readerspodcasts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freedomfighter.readers.speech.summary.LlamaLib
import com.freedomfighter.readers.speech.translate.TranslateModel
import com.freedomfighter.readers.speech.translate.Translator
import com.freedomfighter.readers.speech.whisper.Models
import com.freedomfighter.readers.speech.whisper.Segment
import com.freedomfighter.readers.speech.whisper.WhisperLib
import com.freedomfighter.readerspodcasts.data.Line
import com.freedomfighter.readerspodcasts.data.Transcripts
import com.freedomfighter.readerspodcasts.transcribe.Transcriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writing down what is said, and putting it into another language — one job after the other, in
 * the foreground under a wake lock, with a notification that can stop it.
 *
 * Both take minutes to hours on a telephone, and both happen entirely on it: whisper for the
 * words, a four-billion-parameter model for the translation. Nothing is sent anywhere.
 */
class TranscribeService : Service() {

    private data class Job(
        val id: String,
        val language: String = "",
        val model: String = Models.DEFAULT,
        /** A translation of a transcript already written, rather than a transcription. */
        val translateTo: String = "",
        /** Fetch the weights of the translating model and nothing else. */
        val fetchTranslator: Boolean = false,
    )

    private val queue = ConcurrentLinkedQueue<Job>()
    private val cancelled = AtomicBoolean(false)
    private var running = false
    private var lastStartId = 0
    private var lock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val app get() = application as App

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        goForeground()
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true); queue.clear(); Live.waiting.clear()
                WhisperLib.cancel(); LlamaLib.cancel()
                if (!running) finish()
                return START_NOT_STICKY
            }
            ACTION_FETCH_TRANSLATOR -> if (queue.none { it.fetchTranslator }) {
                queue.add(Job("", fetchTranslator = true))
            }
            else -> intent?.getStringExtra(EXTRA_ID)?.let { id ->
                val target = intent.getStringExtra(EXTRA_TRANSLATE_TO).orEmpty()
                if (queue.none { it.id == id && it.translateTo == target } && !(Live.id == id && Live.phase.isNotBlank())) {
                    queue.add(
                        Job(
                            id,
                            intent.getStringExtra(EXTRA_LANGUAGE).orEmpty(),
                            intent.getStringExtra(EXTRA_MODEL) ?: Models.DEFAULT,
                            target,
                        )
                    )
                    Live.waiting.add(id)
                }
            }
        }
        if (!running) { running = true; cancelled.set(false); scope.launch { work(); finish() } }
        return START_NOT_STICKY
    }

    private suspend fun work() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadersPodcasts:transcribe")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        val ticker = scope.launch { while (isActive) { pushNotification(); delay(1500) } }
        try {
            while (true) {
                val job = queue.poll() ?: break
                cancelled.set(false)
                Live.waiting.remove(job.id)
                Live.id = job.id
                if (Live.errorId == job.id) { Live.errorId = ""; Live.error = "" }
                try {
                    when {
                        job.fetchTranslator -> fetchTranslator()
                        job.translateTo.isNotBlank() -> translate(job)
                        else -> transcribe(job)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ReadersPodcasts", "transcription failed", e)
                    if (!cancelled.get()) {
                        Live.error = (e.message ?: e.javaClass.simpleName).trim().take(400)
                        Live.errorId = job.id
                    }
                } finally {
                    Live.id = ""; Live.phase = ""; Live.percent = 0
                }
            }
        } finally {
            ticker.cancel()
            Live.id = ""; Live.phase = ""; Live.percent = 0; Live.waiting.clear()
            runCatching { if (lock?.isHeld == true) lock?.release() }
            running = false
        }
    }

    // ---- the work itself ----

    private suspend fun transcribe(job: Job) {
        val episode = app.store.episode(job.id) ?: return
        val result = withContext(Dispatchers.Default) {
            Transcriber.run(
                this@TranscribeService, episode, job.language.ifBlank { null }, job.model,
                { phase, percent -> Live.phase = phase; Live.percent = percent },
                { cancelled.get() },
            )
        } ?: return
        if (cancelled.get()) return
        Live.phase = PHASE_SAVE
        val (heard, lines) = result
        withContext(Dispatchers.IO) {
            Transcripts.save(this@TranscribeService, job.id, heard, lines)
            // The .txt goes out to Documents so any reader opens it; the app keeps the lines.
            val uri = runCatching { Transcriber.export(this@TranscribeService, episode, Transcripts.asText(lines)) }.getOrNull()
            app.store.updateEpisode(job.id) {
                it.copy(transcript = true, transcriptLanguage = heard, transcriptUri = uri?.toString() ?: it.transcriptUri)
            }
        }
    }

    private suspend fun translate(job: Job) {
        val episode = app.store.episode(job.id) ?: return
        val source = Transcripts.load(this, job.id) ?: return
        if (!TranslateModel.isDownloaded(this)) {
            Live.phase = PHASE_MODEL
            withContext(Dispatchers.IO) {
                TranslateModel.download(this@TranscribeService, { Live.percent = it }, { cancelled.get() })
            }
        }
        if (cancelled.get()) return
        val handle = TranslateModel.open(this) ?: error(getString(R.string.model_not_here))
        Live.phase = PHASE_TRANSLATE
        Live.percent = 0
        val blocks = withContext(Dispatchers.Default) {
            handle.use {
                Translator.translate(
                    handle.path,
                    source.second.map { Segment(it.startMs, it.endMs, it.text) },
                    job.translateTo,
                    { Live.percent = it },
                    { cancelled.get() },
                )
            }
        } ?: return
        if (cancelled.get()) return
        Live.phase = PHASE_SAVE
        withContext(Dispatchers.IO) {
            Transcripts.saveTranslation(
                this@TranscribeService, job.id, job.translateTo,
                blocks.map { Line(it.startMs, it.endMs, it.text) },
            )
            app.store.updateEpisode(job.id) { it.copy(translation = job.translateTo) }
        }
    }

    private suspend fun fetchTranslator() {
        Live.phase = PHASE_MODEL
        withContext(Dispatchers.IO) {
            TranslateModel.download(this@TranscribeService, { Live.percent = it }, { cancelled.get() })
        }
    }

    // ---- the service around it ----

    private fun finish() {
        if (running) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (lock?.isHeld == true) lock?.release() }
        super.onDestroy()
    }

    private fun goForeground() =
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun pushNotification() =
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_transcription), NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); enableVibration(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = app.store.episode(Live.id)?.title ?: getString(R.string.app_name)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(phaseLabel(this, Live.phase, Live.percent))
            .setSmallIcon(R.drawable.ic_note)
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
            .setProgress(100, Live.percent, Live.phase.isEmpty() || Live.phase == PHASE_SAVE)
            .addAction(0, getString(R.string.stop), cancel)
            .build()
    }

    /** What the screens and the notification show about the work in hand. */
    object Live {
        var id by mutableStateOf("")
        var phase by mutableStateOf("")   // model | transcribe | translate | save
        var percent by mutableIntStateOf(0)
        var error by mutableStateOf("")
        var errorId by mutableStateOf("")
        val waiting = mutableStateListOf<String>()

        fun busy(which: String) = which == id || which in waiting
    }

    companion object {
        const val ACTION_CANCEL = "com.freedomfighter.readerspodcasts.TRANSCRIBE_CANCEL"
        const val ACTION_FETCH_TRANSLATOR = "com.freedomfighter.readerspodcasts.FETCH_TRANSLATOR"
        const val EXTRA_ID = "id"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_MODEL = "model"
        const val EXTRA_TRANSLATE_TO = "translate_to"
        const val PHASE_MODEL = "model"
        const val PHASE_TRANSCRIBE = "transcribe"
        const val PHASE_TRANSLATE = "translate"
        const val PHASE_SAVE = "save"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIF_ID = 9

        fun phaseLabel(ctx: Context, phase: String, percent: Int): String = when (phase) {
            PHASE_MODEL -> ctx.getString(R.string.phase_model, percent)
            PHASE_TRANSCRIBE -> ctx.getString(R.string.phase_transcribe, percent)
            PHASE_TRANSLATE -> ctx.getString(R.string.phase_translate, percent)
            PHASE_SAVE -> ctx.getString(R.string.phase_save)
            else -> ctx.getString(R.string.phase_waiting)
        }

        fun transcribe(ctx: Context, id: String, language: String, model: String) =
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, TranscribeService::class.java)
                    .putExtra(EXTRA_ID, id).putExtra(EXTRA_LANGUAGE, language).putExtra(EXTRA_MODEL, model)
            )

        fun translate(ctx: Context, id: String, target: String) =
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, TranscribeService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TRANSLATE_TO, target)
            )

        fun fetchTranslator(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, TranscribeService::class.java).setAction(ACTION_FETCH_TRANSLATOR)
        )

        fun cancel(ctx: Context) {
            if (Live.id.isNotBlank() || Live.waiting.isNotEmpty()) {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, TranscribeService::class.java).setAction(ACTION_CANCEL)
                )
            }
        }
    }
}
