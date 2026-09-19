package com.freedomfighter.readerspodcasts.data

/** One chapter of an episode: where it starts, and what it is called. */
data class Chapter(val startMs: Long, val title: String)

/**
 * The chapters an episode carries in its own description.
 *
 * Both worlds write them the same way, as a list of times and titles — a podcast in its show
 * notes, a video in the text under it, which is exactly what YouTube reads to draw its own
 * chapter marks. So nothing has to be fetched: what is already on the screen is the source.
 *
 * The danger is reading a list where there is none: a description often mentions one time in
 * passing (« à 12:30 il raconte… »). So a list is only a list when it has at least two times, in
 * order, and none of them beyond the end of the episode.
 */
object Chapters {
    private const val MIN = 2

    // 1:02, 01:02, 1:02:03 — at the head of the line, possibly behind a bullet or a bracket, or
    // at its tail behind a dash. Both are common, and a title never sits on both sides.
    private val HEAD = Regex("""^[\s\-–—*•>\[(]*(\d{1,2}:)?(\d{1,2}):(\d{2})[\s\-–—:•|)\]]*(.*)$""")
    private val TAIL = Regex("""^(.*?)[\s\-–—:•|(\[]+(\d{1,2}:)?(\d{1,2}):(\d{2})[\s)\]]*$""")

    /**
     * The chapters of [description], or nothing when it holds no list. [durationMs], when it is
     * known, throws out a reading whose times run past the end of the sound.
     */
    fun parse(description: String, durationMs: Long = 0): List<Chapter> {
        val found = ArrayList<Chapter>()
        description.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val head = HEAD.matchEntire(line)
            val match = if (head != null && head.groupValues[4].isNotBlank()) head else TAIL.matchEntire(line)
            if (match == null) return@forEach
            val (title, h, m, sec) =
                if (match === head) listOf(match.groupValues[4], match.groupValues[1], match.groupValues[2], match.groupValues[3])
                else listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
            val hours = h.trimEnd(':').toIntOrNull() ?: 0
            val minutes = m.toIntOrNull() ?: return@forEach
            val seconds = sec.toIntOrNull() ?: return@forEach
            if (seconds > 59 || (hours > 0 && minutes > 59)) return@forEach
            val clean = title.trim().trim('-', '–', '—', ':', '•', '|', '.', ' ')
            if (clean.isEmpty()) return@forEach
            found.add(Chapter((hours * 3600L + minutes * 60L + seconds) * 1000L, clean))
        }
        if (found.size < MIN) return emptyList()
        // In order, and inside the episode: otherwise it is prose that happens to name times.
        if (found.zipWithNext().any { (a, b) -> b.startMs <= a.startMs }) return emptyList()
        if (durationMs > 0 && found.last().startMs >= durationMs) return emptyList()
        return found
    }

    /** The chapter one is inside at [positionMs], or null before the first. */
    fun at(chapters: List<Chapter>, positionMs: Long): Chapter? =
        chapters.lastOrNull { it.startMs <= positionMs }
}
