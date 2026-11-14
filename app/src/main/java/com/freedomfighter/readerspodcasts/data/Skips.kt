package com.freedomfighter.readerspodcasts.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * A stretch of a video that people using SponsorBlock marked as not part of it: a sponsor's
 * message, the author promoting their own things, or a reminder to subscribe.
 */
data class SkipSegment(val startMs: Long, val endMs: Long, val category: String, val uuid: String)

/**
 * SponsorBlock, as far as this app needs it: the address to ask, and what comes back.
 *
 * The address carries only the first four characters of the SHA-256 of the video id. The answer
 * lists every video whose fingerprint begins that way, and the one wanted is picked out here, on
 * the phone: the server never learns which video is being heard.
 */
object SponsorBlock {
    val CATEGORIES = listOf("sponsor", "selfpromo", "interaction")
    private const val API = "https://sponsor.ajay.app/api/skipSegments/"

    fun prefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(4)

    fun url(videoId: String): String =
        API + prefix(videoId) + "?categories=" + URLEncoder.encode(JSONArray(CATEGORIES).toString(), "UTF-8")

    /**
     * The segments of [videoId] in an answer, in order. Only those meant to be skipped: SponsorBlock
     * also knows « mute » and « full » (the whole video is a sponsor), which a jump cannot honour.
     */
    fun parse(json: String, videoId: String): List<SkipSegment> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("videoID") != videoId) continue
            val segments = o.optJSONArray("segments") ?: return emptyList()
            return (0 until segments.length()).mapNotNull { j ->
                val s = segments.optJSONObject(j) ?: return@mapNotNull null
                if (s.optString("actionType", "skip") != "skip") return@mapNotNull null
                val category = s.optString("category")
                if (category !in CATEGORIES) return@mapNotNull null
                val range = s.optJSONArray("segment") ?: return@mapNotNull null
                if (range.length() < 2) return@mapNotNull null
                val start = (range.optDouble(0, -1.0) * 1000).toLong()
                val end = (range.optDouble(1, -1.0) * 1000).toLong()
                if (start < 0 || end <= start) return@mapNotNull null
                SkipSegment(start, end, category, s.optString("UUID"))
            }.sortedBy { it.startMs }
        }
        return emptyList()
    }

    /** As kept with the episode, in its feed's file. */
    fun toJson(segments: List<SkipSegment>): JSONArray = JSONArray().apply {
        segments.forEach { s ->
            put(JSONObject().apply { put("start", s.startMs); put("end", s.endMs); put("category", s.category); put("uuid", s.uuid) })
        }
    }

    fun fromJson(arr: JSONArray): List<SkipSegment> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        SkipSegment(o.optLong("start"), o.optLong("end"), o.optString("category"), o.optString("uuid"))
    }
}

/** The beginning and the end a listener asked to leave out of every episode of a channel. */
object Skips {
    /** Under this, an episode is taken to start from its beginning. */
    const val BEGINNING_MS = 1_000L

    /**
     * Where an episode starts. From its beginning, past the first [introMs]; anywhere else, where
     * it was left — a place one chose is not second-guessed. An episode no longer than what is
     * to be left out of it plays whole: better too much than nothing.
     */
    fun startAt(positionMs: Long, introMs: Long, durationMs: Long): Long = when {
        introMs <= 0 || positionMs >= BEGINNING_MS -> positionMs
        durationMs > 0 && introMs >= durationMs -> positionMs
        else -> introMs
    }

    /** Where an episode is taken to end when its last [outroMs] are left out, or null for its real end. */
    fun endAt(durationMs: Long, outroMs: Long): Long? =
        if (outroMs <= 0 || durationMs <= 0 || outroMs >= durationMs) null else durationMs - outroMs
}

/**
 * What playback does at the segments of one listening, told apart from a hand that moved it.
 *
 * Playback that runs into a segment jumps over it. A place one went to by hand is left alone —
 * a segment one seeks into is one chose to hear — and so is one taken back to on purpose
 * ([spare]), for the rest of this listening. The same holds for the end of the episode when its
 * last seconds are left out: run into, it is the end; gone to by hand, it plays.
 *
 * Kept free of the player so that every case can be tested: the service reports where playback
 * is ([tick]), where a seek landed ([seeked]), and acts on the answer.
 */
class SkipWatch {
    sealed class Action {
        data object None : Action()
        data class Skip(val segment: SkipSegment) : Action()
        data object End : Action()
    }

    var segments: List<SkipSegment> = emptyList()
    private var last = -1L
    private val spared = HashSet<String>()

    /** A new listening, from [startMs]: one from the beginning counts a segment at 0 as run into. */
    fun begin(startMs: Long) {
        spared.clear()
        last = if (startMs < Skips.BEGINNING_MS) -1L else startMs
    }

    /** Where a seek — the listener's, or this watch's own jump — has put playback. */
    fun seeked(toMs: Long) { last = toMs }

    /** Taken back to on purpose: not jumped over again during this listening. */
    fun spare(uuid: String) { spared.add(uuid) }

    /**
     * Playback has got to [positionMs]. [endMs] is where the episode is taken to end, or null.
     * Answers what to do: jump over a segment just entered, end the episode, or nothing.
     */
    fun tick(positionMs: Long, endMs: Long?): Action {
        val from = last
        last = positionMs
        if (positionMs < from) return Action.None
        val entered = segments.firstOrNull { it.uuid !in spared && from < it.startMs && it.startMs <= positionMs && positionMs < it.endMs }
        if (entered != null) {
            if (endMs != null && entered.endMs >= endMs) return Action.End
            last = entered.endMs
            return Action.Skip(entered)
        }
        if (endMs != null && from < endMs && endMs <= positionMs) return Action.End
        return Action.None
    }
}
