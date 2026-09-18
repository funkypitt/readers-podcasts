package com.freedomfighter.readerspodcasts.transcribe

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.freedomfighter.readers.speech.audio.Decode16k
import com.freedomfighter.readers.speech.whisper.Models
import com.freedomfighter.readers.speech.whisper.Prompts
import com.freedomfighter.readers.speech.whisper.Segment
import com.freedomfighter.readers.speech.whisper.Vad
import com.freedomfighter.readers.speech.whisper.WhisperSession
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Line
import com.freedomfighter.readerspodcasts.data.Transcripts

/**
 * Whisper on the phone, over a whole episode in five-minute pieces.
 *
 * The lines come back with their times and are kept that way, so the reading can follow the
 * sound. Everything stays on the telephone: the model is fetched once, the audio never leaves.
 */
object Transcriber {
    private const val CHUNK_SECONDS = 300

    /** Where the file to transcribe is: the downloaded copy, since streaming cannot be decoded twice. */
    fun source(episode: Episode): Uri? = episode.localPath.takeIf { it.isNotBlank() }?.let { Uri.parse("file://$it") }

    /**
     * The lines of what was said, and the language whisper heard, or null when it was stopped.
     * [onProgress] gets a phase ("model", "transcribe") and 0–100.
     */
    fun run(
        ctx: Context,
        episode: Episode,
        language: String?,
        modelKey: String,
        onProgress: (String, Int) -> Unit,
        cancelled: () -> Boolean,
    ): Pair<String, List<Line>>? {
        val model = Models.byKey(modelKey)
        if (!Models.isDownloaded(ctx, model)) {
            onProgress("model", 0)
            Models.download(ctx, model, onProgress = { onProgress("model", it) }, cancelled = cancelled)
        }
        if (cancelled()) return null
        val uri = source(episode) ?: error(ctx.getString(com.freedomfighter.readerspodcasts.R.string.transcribe_needs_file))
        // Ours, or a sibling app's copy by file descriptor: either way a path whisper reads.
        val handle = Models.open(ctx, model) ?: error(ctx.getString(com.freedomfighter.readerspodcasts.R.string.model_not_here))
        val totalMs = durationMs(ctx, uri).takeIf { it > 0 } ?: episode.durationMs.coerceAtLeast(1)
        val segments = ArrayList<Segment>()
        var heard = language.orEmpty()
        var aborted = false
        onProgress("transcribe", 0)
        handle.use {
            WhisperSession(handle.path, Vad.modelPath(ctx)).use { session ->
                Decode16k.chunks(ctx, uri, CHUNK_SECONDS) { pcm, startMs ->
                    if (cancelled()) { aborted = true; return@chunks false }
                    val chunkMs = pcm.size / 16L
                    val prompt =
                        if (segments.isEmpty()) Prompts.style(language)
                        else Prompts.forPiece(language, segments.takeLast(12).joinToString(" ") { it.text })
                    val segs = session.run(pcm, language, prompt) { p ->
                        onProgress("transcribe", (((startMs + chunkMs * p / 100.0) / totalMs) * 100).toInt().coerceIn(0, 99))
                    }
                    if (segs == null) { aborted = true; return@chunks false }
                    if (heard.isBlank()) heard = runCatching { session.language() }.getOrDefault("")
                    segs.forEach { segments += it.copy(startMs = it.startMs + startMs, endMs = it.endMs + startMs) }
                    true
                }
            }
        }
        if (aborted || cancelled()) return null
        return heard to Transcripts.fromSegments(segments)
    }

    fun durationMs(ctx: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use {
            it.setDataSource(ctx, uri)
            it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        }
    }.getOrDefault(0L)

    const val FOLDER = "Transcriptions"

    /**
     * Save as Documents/Transcriptions/<title>.txt through MediaStore, so any reader opens it;
     * transcribing the same episode again overwrites the file rather than leaving two.
     */
    fun export(ctx: Context, episode: Episode, text: String): Uri {
        val cr = ctx.contentResolver
        val bytes = text.toByteArray(Charsets.UTF_8)
        episode.transcriptUri.takeIf { it.isNotBlank() }?.let { existing ->
            val u = Uri.parse(existing)
            if (runCatching { cr.openOutputStream(u, "wt")!!.use { it.write(bytes) } }.isSuccess) return u
        }
        val name = episode.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim()
            .ifBlank { "transcript" }.take(100) + ".txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER)
        }
        val uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("cannot create $name")
        cr.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }
}
