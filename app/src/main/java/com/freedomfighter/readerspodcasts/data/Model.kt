package com.freedomfighter.readerspodcasts.data

import com.freedomfighter.readerspodcasts.R
import java.security.MessageDigest

/** Where a subscription's episodes come from. YouTube channels are read in the private build only. */
enum class Kind { RSS, YOUTUBE }

/** Where an episode stands for the listener. */
enum class State { NEW, STARTED, PLAYED }

/**
 * One subscription. A YouTube channel is a feed like any other — YouTube publishes
 * `feeds/videos.xml?channel_id=…` — only its media has to be fetched differently.
 */
data class Feed(
    val id: String,
    val url: String,
    val title: String,
    val author: String = "",
    val kind: Kind = Kind.RSS,
    val addedAt: Long = System.currentTimeMillis(),
    val lastFetch: Long = 0,
    /** Download new episodes as they appear, without being asked. */
    val autoDownload: Boolean = false,
    /** How many episodes of this feed to keep in the list. */
    val keepCount: Int = 50,
    /** Empty, or why the last refresh failed. */
    val lastError: String = "",
)

/**
 * One episode. [id] is derived from the feed and the guid, so the same episode keeps its
 * position and its file across refreshes even when the feed reorders or rewrites its items.
 */
data class Episode(
    val id: String,
    val feedId: String,
    val title: String,
    val published: Long,
    val mediaUrl: String,
    val mime: String = "audio/*",
    /** What the feed announces; the real size is known only once downloaded. */
    val bytes: Long = 0,
    val durationMs: Long = 0,
    /** Absolute path of the downloaded file, "" while it is not on the phone. */
    val localPath: String = "",
    val positionMs: Long = 0,
    val state: State = State.NEW,
    val lastPlayed: Long = 0,
    val description: String = "",
    /** Kept by hand, with a star: the one list the app never fills or empties by itself. */
    val starred: Boolean = false,
) {
    val downloaded: Boolean get() = localPath.isNotBlank()
    /** What plays: the file if it is here, the network otherwise. */
    val playUri: String get() = if (downloaded) "file://$localPath" else mediaUrl
}

/** Stable ids, so that ids survive a reinstall and match between the phone and the desktop. */
fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

fun feedId(url: String): String = sha1(url.trim().removeSuffix("/"))

fun episodeId(feedId: String, guid: String): String = sha1("$feedId|$guid")

/**
 * `47 min`, `1 h 12`, `12 s` — a length as one says it, never `00:47:00`. Below the minute it
 * is said in seconds: a rounded `0 min` is not a length, it is a bug on the screen.
 */
fun spoken(context: android.content.Context, ms: Long): String {
    val l = lengthOf(ms)
    return when {
        l.hours > 0 -> context.getString(R.string.duration_h, l.hours, l.minutes)
        l.minutes > 0 -> context.getString(R.string.duration_min, l.minutes)
        else -> context.getString(R.string.duration_s, l.seconds)
    }
}

/** A length broken into what one would say of it. Kept apart from the wording so it can be tested. */
data class Spoken(val hours: Int, val minutes: Int, val seconds: Int)

fun lengthOf(ms: Long): Spoken {
    if (ms < 60_000) return Spoken(0, 0, (ms.coerceAtLeast(0) / 1000).toInt())
    val min = (ms + 30_000) / 60_000
    return Spoken((min / 60).toInt(), (min % 60).toInt(), 0)
}

/**
 * The channels, the one that published last at the top; those with nothing yet fall to the
 * bottom in the order of their names. Kept apart from the store, and typed in Long throughout:
 * `?: 0` here mixed an Int among the Longs, and the comparator threw the moment one channel had
 * no episode at all — which on a hundred and forty subscriptions is a certainty, not a corner.
 */
fun channelsByLatest(feeds: List<Feed>, episodes: List<Episode>): List<Feed> {
    val latest: Map<String, Long> = episodes.groupBy { it.feedId }
        .mapValues { (_, list) -> list.maxOf { it.published } }
    return feeds.sortedWith(
        compareByDescending<Feed> { latest[it.id] ?: 0L }.thenBy { it.title.lowercase() }
    )
}

/** `12:03` / `1:02:45`, for the player where the exact position matters. */
fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
