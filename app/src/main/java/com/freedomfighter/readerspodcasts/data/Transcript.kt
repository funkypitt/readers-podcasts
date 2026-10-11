package com.freedomfighter.readerspodcasts.data

import android.content.Context
import com.freedomfighter.readers.speech.whisper.Paragraphs
import com.freedomfighter.readers.speech.whisper.Segment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One line of a transcript, where it sits in the sound, and whether another voice starts with it. */
data class Line(val startMs: Long, val endMs: Long, val text: String, val newVoice: Boolean = false)

/**
 * What was said, kept as lines with their times.
 *
 * Not as a wall of text: the times are what lets the reading follow the sound, and what lets a
 * tap on a line send the audio there. The exported `.txt` is a rendering of these lines and is
 * never read back — a file the reader may have edited or moved is not a source of truth.
 *
 * A translation is kept the same way, in a file of its own per language, in blocks of some forty
 * seconds rather than line by line: matching a translated sentence to its source only holds three
 * times out of four, and a reading that drifts against the sound is worse than none.
 */
object Transcripts {
    private fun dir(ctx: Context) = File(ctx.filesDir, "transcripts").apply { mkdirs() }

    fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    fun translationFile(ctx: Context, id: String, language: String) = File(dir(ctx), "$id.$language.json")

    fun has(ctx: Context, id: String) = file(ctx, id).isFile
    fun hasTranslation(ctx: Context, id: String, language: String) = translationFile(ctx, id, language).isFile

    fun save(ctx: Context, id: String, language: String, lines: List<Line>) =
        write(file(ctx, id), language, lines)

    fun saveTranslation(ctx: Context, id: String, language: String, lines: List<Line>) =
        write(translationFile(ctx, id, language), language, lines)

    /** The lines and the language they are in, or null when there is no transcript. */
    fun load(ctx: Context, id: String): Pair<String, List<Line>>? = read(file(ctx, id))

    fun loadTranslation(ctx: Context, id: String, language: String): List<Line>? =
        read(translationFile(ctx, id, language))?.second

    fun forget(ctx: Context, id: String) {
        dir(ctx).listFiles { f -> f.name.startsWith("$id.") || f.name == "$id.json" }?.forEach { it.delete() }
    }

    /** Where the paragraphs begin: the shared rule — another voice, the pauses, the long paragraphs. */
    fun starts(lines: List<Line>): BooleanArray =
        Paragraphs.starts(lines.map { Segment(it.startMs, it.endMs, it.text, it.newVoice) })

    /** What leaves the app: the lines run together into paragraphs. */
    fun asText(lines: List<Line>): String {
        val kept = lines.filter { it.text.isNotBlank() }
        val starts = starts(kept)
        val out = StringBuilder()
        kept.forEachIndexed { i, line ->
            if (out.isEmpty()) out.append(line.text.trim())
            else if (starts[i]) out.append("\n\n").append(line.text.trim())
            else out.append(' ').append(line.text.trim())
        }
        return out.toString()
    }

    fun fromSegments(segments: List<Segment>): List<Line> =
        segments.map { Line(it.startMs, it.endMs, it.text.trim(), it.newVoice) }.filter { it.text.isNotEmpty() }

    private fun write(target: File, language: String, lines: List<Line>) {
        val root = JSONObject()
        root.put("language", language)
        // "p" marks every line a paragraph begins with (the desktop app believes them), "v" the
        // lines where another voice starts; a file without "p" is cut on the pauses when read.
        val starts = starts(lines)
        root.put("lines", JSONArray().apply {
            lines.forEachIndexed { i, it -> put(JSONObject().apply {
                put("a", it.startMs); put("b", it.endMs); put("t", it.text)
                if (starts[i]) put("p", 1)
                if (it.newVoice) put("v", 1)
            }) }
        })
        runCatching { target.writeText(root.toString()) }
    }

    private fun read(source: File): Pair<String, List<Line>>? = runCatching {
        val root = JSONObject(source.readText())
        val arr = root.getJSONArray("lines")
        val lines = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Line(o.optLong("a"), o.optLong("b"), o.optString("t"), o.optInt("v") == 1)
        }
        root.optString("language") to lines
    }.getOrNull()
}
