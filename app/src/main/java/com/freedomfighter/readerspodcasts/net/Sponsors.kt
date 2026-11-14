package com.freedomfighter.readerspodcasts.net

import com.freedomfighter.readerspodcasts.App
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Lengths
import com.freedomfighter.readerspodcasts.data.SkipSegment
import com.freedomfighter.readerspodcasts.data.SponsorBlock

/**
 * Asking SponsorBlock for the sponsors of a YouTube video, as part of something the listener
 * started — its download, or playing it — and never by itself. What comes back is kept with the
 * episode; a question that failed (no network, a server down) is simply asked again next time.
 */
object Sponsors {
    /** Whether [e] is a video worth asking about, and has not been asked about yet. */
    fun wanted(app: App, e: Episode): Boolean =
        app.prefs.settings.value.skipSponsors && e.skipSegments == null &&
            e.mediaUrl.contains("youtube.com/") && Lengths.videoId(e.mediaUrl) != null

    /** Blocking: call off the main thread. Returns what was kept, or null when nothing could be learnt. */
    fun lookUp(app: App, e: Episode): List<SkipSegment>? {
        if (!wanted(app, e)) return null
        val id = Lengths.videoId(e.mediaUrl) ?: return null
        val found = runCatching {
            val c = Net.open(SponsorBlock.url(id))
            try {
                when (c.responseCode) {
                    // No segment for any video of this fingerprint: a real answer, kept as such.
                    404 -> emptyList()
                    in 200..299 -> SponsorBlock.parse(Net.body(c).bufferedReader().readText(), id)
                    else -> null
                }
            } finally {
                runCatching { c.disconnect() }
            }
        }.getOrNull() ?: return null
        app.store.updateEpisode(e.id) { it.copy(skipSegments = found) }
        return found
    }
}
