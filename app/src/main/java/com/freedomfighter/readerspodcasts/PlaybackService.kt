package com.freedomfighter.readerspodcasts

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.State
import com.freedomfighter.readerspodcasts.data.SkipSegment
import com.freedomfighter.readerspodcasts.data.SkipWatch
import com.freedomfighter.readerspodcasts.data.Skips
import com.freedomfighter.readerspodcasts.data.autoDeletable
import com.freedomfighter.readerspodcasts.net.Sponsors
import com.freedomfighter.readerspodcasts.widget.LastWidgets
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** What plays, for the widget (same process). [at] is when [positionMs] was read. */
object NowPlaying {
    @Volatile var id: String = ""
    @Volatile var playing: Boolean = false
    @Volatile var positionMs: Long = 0L
    @Volatile var durationMs: Long = 0L
    @Volatile var at: Long = 0L
    /** The last stretch jumped over, for the player's « go back »; null when none. */
    @Volatile var skipped: Skipped? = null
}

/** A segment jumped over in [episodeId], at [at] (wall clock). */
data class Skipped(val episodeId: String, val segment: SkipSegment, val at: Long)

/**
 * Playback, the standard way: Media3's ExoPlayer inside a MediaSessionService. The session
 * gives the system media notification, the lock screen, Bluetooth and headset buttons, and
 * playback resumption for the widget. The notification carries −5 s, play/pause, +10 s and
 * stop; there is no previous/next, one episode plays at a time. The position is saved every
 * few seconds, so an episode always resumes where it was left — which for a podcast heard in
 * ten sittings is the whole point.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as App
    private val saver = object : Runnable {
        override fun run() {
            savePosition()
            if (session?.player?.isPlaying == true) handler.postDelayed(this, 5_000)
        }
    }

    /**
     * What is left out: the sponsors of a video, and the end of an episode when its channel says
     * to leave its last seconds out. Watched here, in the service, so that it holds with the
     * screen off; five times a second, which lets through a fifth of a second at most.
     */
    private val watch = SkipWatch()
    /** The episode just ran to its end: a start from 0 after that is a new listening. */
    private var endedOnce = false
    private val watcher = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            if (!p.isPlaying) return
            check(p)
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val unskip = SessionCommand(ACTION_UNSKIP, Bundle.EMPTY)
        player.setPlaybackSpeed(app.prefs.settings.value.speed)
        // The system media controls (Android 13+) only show a session's CUSTOM actions besides
        // play/pause: buttons bound to player commands (seek back/forward) never appear there.
        // So −5 s, +10 s and stop are all session commands, handled in onCustomCommand.
        val back = SessionCommand(ACTION_BACK5, Bundle.EMPTY)
        val fwd = SessionCommand(ACTION_FWD10, Bundle.EMPTY)
        val stop = SessionCommand(ACTION_STOP, Bundle.EMPTY)
        val layout = ImmutableList.of(
            CommandButton.Builder().setDisplayName(getString(R.string.back5)).setIconResId(R.drawable.ic_replay_5).setSessionCommand(back).build(),
            CommandButton.Builder().setDisplayName(getString(R.string.fwd10)).setIconResId(R.drawable.ic_forward_10).setSessionCommand(fwd).build(),
            CommandButton.Builder().setDisplayName(getString(R.string.stop)).setIconResId(R.drawable.ic_stop).setSessionCommand(stop).build()
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCustomLayout(layout)
            .setCallback(SessionCallback(listOf(back, fwd, stop, unskip)))
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(R.drawable.ic_note) })
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(saver)
                handler.removeCallbacks(watcher)
                if (isPlaying) { handler.post(saver); handler.post(watcher) } else savePosition()
                changed()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return
                val p = session?.player ?: return
                // Heard through, then started again from the top: a listening of its own.
                if (endedOnce && newPosition.positionMs < Skips.BEGINNING_MS) {
                    endedOnce = false
                    begin(p)
                    return
                }
                watch.seeked(newPosition.positionMs)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                session?.player?.let { endedOnce = false; begin(it) }
                mediaItem?.mediaId?.let { id ->
                    app.store.updateEpisode(id) {
                        it.copy(lastPlayed = System.currentTimeMillis(), state = if (it.state == State.NEW) State.STARTED else it.state)
                    }
                }
                changed()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                val p = session?.player
                val id = p?.currentMediaItem?.mediaId
                if (p != null && id != null) {
                    if (playbackState == Player.STATE_READY && p.duration > 0) app.store.updateEpisode(id) { if (it.durationMs == p.duration) it else it.copy(durationMs = p.duration) }
                    if (playbackState == Player.STATE_ENDED) { endedOnce = true; finished(id) }
                }
                changed()
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlaybackService, R.string.cant_open, Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) { savePosition(); stopSelf() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saver)
        handler.removeCallbacks(watcher)
        savePosition()
        session?.run { player.release(); release() }
        session = null
        NowPlaying.id = ""; NowPlaying.playing = false
        LastWidgets.refresh(this)
        super.onDestroy()
    }

    /**
     * A listening begins: past the channel's opening seconds when it starts from the beginning,
     * with the video's sponsors when they are known — and asked for when they are not.
     */
    private fun begin(p: Player) {
        NowPlaying.skipped = null
        val e = p.currentMediaItem?.mediaId?.let { app.store.episode(it) }
        if (e == null) { watch.segments = emptyList(); watch.begin(0); return }
        val feed = app.store.feed(e.feedId)
        val at = p.currentPosition
        val start = Skips.startAt(at, (feed?.skipStartSec ?: 0) * 1000L, p.duration.takeIf { it > 0 } ?: e.durationMs)
        watch.segments = e.skipSegments.orEmpty()
        watch.begin(at)
        if (start != at) p.seekTo(start)
        // What comes back lands with the episode, where the watch reads it.
        if (Sponsors.wanted(app, e)) Thread { Sponsors.lookUp(app, e) }.start()
    }

    /** Where playback has got to, against what is to be left out of it. */
    private fun check(p: Player) {
        val id = p.currentMediaItem?.mediaId ?: return
        val e = app.store.episode(id)
        val feed = e?.let { app.store.feed(it.feedId) }
        val end = Skips.endAt(p.duration.takeIf { it > 0 } ?: 0L, (feed?.skipEndSec ?: 0) * 1000L)
        watch.segments = if (app.prefs.settings.value.skipSponsors) e?.skipSegments.orEmpty() else emptyList()
        when (val action = watch.tick(p.currentPosition, end)) {
            is SkipWatch.Action.Skip -> {
                NowPlaying.skipped = Skipped(id, action.segment, System.currentTimeMillis())
                p.seekTo(action.segment.endMs)
            }
            // The end one asked for is the end: the player runs out as it would at the real one.
            SkipWatch.Action.End -> p.seekTo(p.duration.takeIf { it > 0 } ?: return)
            SkipWatch.Action.None -> Unit
        }
    }

    private fun savePosition() {
        val p = session?.player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        if (p.playbackState == Player.STATE_ENDED) return
        val pos = p.currentPosition
        val dur = p.duration
        app.store.updateEpisode(id) {
            it.copy(
                positionMs = pos, durationMs = if (dur > 0) dur else it.durationMs,
                state = if (it.state == State.NEW && pos > 0) State.STARTED else it.state,
            )
        }
    }

    /**
     * An episode heard through: marked as played, put back to its beginning, and — if that is
     * the setting, and the episode is not one of those worth keeping — its file deleted. See
     * [autoDeletable]: a favourite, something written down, and anything from YouTube stay.
     */
    private fun finished(id: String) {
        app.store.updateEpisode(id) { it.copy(positionMs = 0, state = State.PLAYED) }
        if (!app.prefs.settings.value.deleteWhenPlayed) return
        val episode = app.store.episodes.value.firstOrNull { it.id == id } ?: return
        if (autoDeletable(episode, app.store.feed(episode.feedId)?.kind)) app.store.deleteFile(id)
    }

    private fun changed() {
        val p = session?.player
        NowPlaying.id = p?.currentMediaItem?.mediaId ?: ""
        NowPlaying.playing = p?.isPlaying == true
        NowPlaying.positionMs = p?.currentPosition ?: 0L
        NowPlaying.durationMs = p?.duration?.takeIf { it > 0 } ?: 0L
        NowPlaying.at = System.currentTimeMillis()
        LastWidgets.refresh(this)
    }

    /** Stop: the position is kept, the notification goes, the service ends. */
    private fun stopPlayback() {
        savePosition()
        session?.player?.let { it.stop(); it.clearMediaItems() }
        changed()
        stopSelf()
    }

    /**
     * A MediaItem from the controller carries only its id; where it plays from is decided here —
     * the downloaded file when there is one, the network otherwise, so an episode can be started
     * before it has come down.
     */
    private fun resolve(m: MediaItem): MediaItem {
        val e = app.store.episode(m.mediaId) ?: return m
        return m.buildUpon().setUri(Uri.parse(e.playUri)).setMediaMetadata(metadata(e)).build()
    }

    /** The lock screen shows the episode and the channel it comes from. */
    private fun metadata(e: Episode): MediaMetadata = MediaMetadata.Builder()
        .setTitle(e.title)
        .setArtist(app.store.feed(e.feedId)?.title ?: "")
        .build()

    private inner class SessionCallback(private val commands: List<SessionCommand>) : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply { commands.forEach { add(it) } }.build()
            // No previous / next: their slots in the system media controls go to −5 s and +10 s.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_BACK5 -> session.player.seekBack()
                ACTION_FWD10 -> session.player.seekForward()
                ACTION_STOP -> stopPlayback()
                // « Go back »: to the start of what was jumped over, which then plays.
                ACTION_UNSKIP -> {
                    val uuid = args.getString(EXTRA_UUID).orEmpty()
                    watch.spare(uuid)
                    NowPlaying.skipped = null
                    session.player.seekTo(args.getLong(EXTRA_START))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * The widget's ▶ arrives here as a media button. Media3 ignores play on an ended or idle
         * player, and a media button starts this service in the foreground: if nothing then plays,
         * Android kills the app after a few seconds. So an ended file starts again, an idle one is
         * prepared, and an empty player loads the last file.
         */
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            if (key.action != KeyEvent.ACTION_DOWN) return false
            if (key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY) return false
            val p = session.player
            when {
                p.mediaItemCount == 0 -> {
                    val last = app.store.last() ?: return false
                    val start = if (last.durationMs > 0 && last.positionMs > last.durationMs - 3_000) 0L else last.positionMs
                    p.setMediaItem(resolve(mediaItem(last)), start); p.prepare(); p.play()
                }
                p.playbackState == Player.STATE_ENDED -> { p.seekTo(0); p.play() }
                p.playbackState == Player.STATE_IDLE -> { p.prepare(); p.play() }
                else -> return false
            }
            return true
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { resolve(it) }.toMutableList())

        /** The widget's ▶ after the service was gone: the last file, where it was left. */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val last = app.store.last() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("nothing played yet"))
            val start = if (last.durationMs > 0 && last.positionMs > last.durationMs - 3_000) 0L else last.positionMs
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(resolve(mediaItem(last))), 0, start))
        }
    }

    companion object {
        const val ACTION_STOP = "com.freedomfighter.readerspodcasts.STOP"
        const val ACTION_BACK5 = "com.freedomfighter.readerspodcasts.BACK5"
        const val ACTION_FWD10 = "com.freedomfighter.readerspodcasts.FWD10"
        const val ACTION_UNSKIP = "com.freedomfighter.readerspodcasts.UNSKIP"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_START = "start"
        fun mediaItem(e: Episode): MediaItem = MediaItem.Builder()
            .setMediaId(e.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title).build())
            .build()
    }
}
