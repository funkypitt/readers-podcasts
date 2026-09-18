package com.freedomfighter.readerspodcasts.data

import android.content.Context
import com.freedomfighter.readers.speech.whisper.Segment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One line of a transcript, and where it sits in the sound. */
data class Line(val startMs: Long, val endMs: Long, val text: String)

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

    /** What leaves the app: the lines run together into paragraphs, on a pause of over a second. */
    fun asText(lines: List<Line>): String {
        val out = StringBuilder()
        var lastEnd = -1L
        lines.forEach { line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@forEach
            if (out.isEmpty()) out.append(text)
            else if (lastEnd >= 0 && line.startMs - lastEnd > 1_200) out.append("\n\n").append(text)
            else out.append(' ').append(text)
            lastEnd = line.endMs
        }
        return out.toString()
    }

    fun fromSegments(segments: List<Segment>): List<Line> =
        segments.map { Line(it.startMs, it.endMs, it.text.trim()) }.filter { it.text.isNotEmpty() }

    private fun write(target: File, language: String, lines: List<Line>) {
        val root = JSONObject()
        root.put("language", language)
        root.put("lines", JSONArray().apply {
            lines.forEach { put(JSONObject().apply { put("a", it.startMs); put("b", it.endMs); put("t", it.text) }) }
        })
        runCatching { target.writeText(root.toString()) }
    }

    private fun read(source: File): Pair<String, List<Line>>? = runCatching {
        val root = JSONObject(source.readText())
        val arr = root.getJSONArray("lines")
        val lines = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Line(o.optLong("a"), o.optLong("b"), o.optString("t"))
        }
        root.optString("language") to lines
    }.getOrNull()
}
