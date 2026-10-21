package com.freedomfighter.readerspodcasts.data

import com.freedomfighter.readerspodcasts.net.Extractor

/**
 * The length of a YouTube video. The channel's feed never gives one, and the setting that
 * leaves out the videos under ten minutes needs it: a video without a length is never hidden.
 * A flat listing of the channel's page gives the lengths of its latest videos in one request;
 * the shorts are not on that page and are asked for one by one, a handful at each refresh.
 */
object Lengths {
    /** A length asked for and not given back (an upcoming stream, a premiere): not asked again. */
    const val UNKNOWN = -1L

    /** `watch?v=ID`, `/shorts/ID` — the feed links a short that way — or `youtu.be/ID`. */
    fun videoId(url: String): String? =
        Regex("[?&]v=([\\w-]+)|/shorts/([\\w-]+)|youtu\\.be/([\\w-]+)").find(url)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

    /** The videos of a channel whose length is still to be asked for. */
    fun lacking(episodes: List<Episode>): List<Episode> =
        episodes.filter { it.durationMs == 0L && it.mediaUrl.contains("youtube.com/") }

    /** Episode id → length, for every episode without one that the listing knows. */
    fun fromListing(episodes: List<Episode>, listed: List<Extractor.Listed>): Map<String, Long> {
        val known = listed.filter { it.durationMs > 0 }.associate { it.videoId to it.durationMs }
        return lacking(episodes).mapNotNull { e -> known[videoId(e.mediaUrl)]?.let { e.id to it } }.toMap()
    }
}
