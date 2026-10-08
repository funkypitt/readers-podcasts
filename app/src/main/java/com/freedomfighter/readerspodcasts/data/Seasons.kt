package com.freedomfighter.readerspodcasts.data

/**
 * The seasons of a channel — the desktop's `shelves`, rule for rule.
 *
 * A feed may number its seasons and its episodes, and may call itself a serial: something meant
 * to be heard from its first episode on. A list that only knows dates shows such a channel
 * backwards and in one piece, and its seasons are then nowhere to be found.
 */
object Seasons {

    /** One season and its episodes, in the order they are meant to be heard. 0 is "no season". */
    data class Shelf(val season: Int, val name: String, val episodes: List<Episode>)

    /** A serial from its first episode on, anything else the latest first. */
    fun inOrder(episodes: List<Episode>, serial: Boolean): List<Episode> =
        if (serial) episodes.sortedWith(compareBy<Episode> { it.number <= 0 }.thenBy { it.number }.thenBy { it.published })
        else episodes.sortedByDescending { it.published }

    /**
     * The seasons of one channel, or nothing when the feed numbers no season, or only one — in
     * which case headings would say nothing.
     *
     * A serial is read from season 1 down; a show that merely numbers its years has the current
     * season at the top. What carries no season (a trailer, an extra) comes last.
     */
    fun shelves(episodes: List<Episode>, serial: Boolean): List<Shelf> {
        val groups = episodes.groupBy { it.season }
        if (groups.keys.none { it > 0 } || groups.size < 2) return emptyList()
        val numbered = groups.keys.filter { it > 0 }.let { if (serial) it.sorted() else it.sortedDescending() }
        return (numbered + listOfNotNull(0.takeIf { it in groups })).map { s ->
            val rows = inOrder(groups.getValue(s), serial)
            Shelf(s, rows.firstOrNull { it.seasonName.isNotBlank() }?.seasonName.orEmpty(), rows)
        }
    }

    /** The season to show open when nothing was chosen: the one last listened to, or the first on the page. */
    fun open(shelves: List<Shelf>, episodes: List<Episode>): Int =
        episodes.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }?.season
            ?: shelves.firstOrNull()?.season ?: 0
}
