package com.freedomfighter.readerspodcasts

import android.Manifest
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.freedomfighter.readerspodcasts.data.Backup
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Opml
import com.freedomfighter.readerspodcasts.data.State
import com.freedomfighter.readerspodcasts.net.DownloadService
import com.freedomfighter.readerspodcasts.net.Refresher
import com.freedomfighter.readerspodcasts.ui.HomeScreen
import com.freedomfighter.readerspodcasts.ui.LocalColors
import com.freedomfighter.readerspodcasts.ui.Nav
import com.freedomfighter.readerspodcasts.ui.PlayerScreen
import com.freedomfighter.readerspodcasts.ui.SearchScreen
import com.freedomfighter.readerspodcasts.ui.ReaderTheme
import com.freedomfighter.readerspodcasts.ui.Screen
import com.freedomfighter.readerspodcasts.ui.SettingsScreen
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the media controller reports, polled for the screens. */
class PlayerUi {
    var mediaId by mutableStateOf("")
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
}

class MainActivity : ComponentActivity() {
    val nav = Nav()
    val ui = PlayerUi()
    val app get() = application as App
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingPlay: Episode? = null

    /**
     * What is going on, in one line, or "". An OPML export of a hundred and forty feeds takes
     * minutes to fetch; a screen that only said "reading the feed…" the whole time would look
     * stuck, so it counts them off.
     */
    var busy by mutableStateOf("")
        private set

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ---- the two files that carry everything: subscriptions, and settings with positions ----

    private val writeOpml = registerForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        if (uri == null) return@registerForActivityResult
        write(uri, Opml.export(app.store.feeds.value), R.string.opml_exported)
    }

    private val readOpml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)!!.use { Opml.parse(it) } }.getOrDefault(emptyList())
            }
            if (lines.isEmpty()) { toast(getString(R.string.opml_empty)); return@launch }
            var added = 0
            lines.forEachIndexed { i, line ->
                busy = getString(R.string.reading_feeds, i + 1, lines.size)
                val known = app.store.feeds.value.any { it.url == line.url }
                if (!known && Refresher.add(this@MainActivity, app.store, line.url).isSuccess) added++
            }
            busy = ""
            toast(resources.getQuantityString(R.plurals.feeds_added, added, added))
        }
    }

    private val writeBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        write(uri, Backup.export(app.prefs.exportMap(), app.store.feeds.value, app.store.episodes.value), R.string.settings_exported)
    }

    private val readBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching { Backup.parse(contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }) }.getOrNull()
            }
            if (restored == null) { toast(getString(R.string.settings_unreadable)); return@launch }
            app.prefs.importMap(restored.settings)
            // Positions first: they apply to episodes already here, and to those the feeds below
            // are about to bring in — which is why the states are kept and applied again after.
            applyStates(restored.states)
            restored.feeds.forEachIndexed { i, f ->
                busy = getString(R.string.reading_feeds, i + 1, restored.feeds.size)
                if (app.store.feeds.value.none { it.url == f.url }) Refresher.add(this@MainActivity, app.store, f.url)
                app.store.updateFeed(f.id) { it.copy(autoDownload = f.autoDownload, keepCount = f.keepCount) }
            }
            // Again, because the feeds just fetched brought in the episodes those positions name.
            applyStates(restored.states)
            busy = ""
            toast(getString(R.string.settings_imported))
        }
    }

    private fun applyStates(states: List<Backup.EpisodeState>) {
        states.forEach { s ->
            app.store.updateEpisode(s.id) {
                // The newer of the two sides wins, so importing an older file never undoes
                // listening done since.
                // The star is not a matter of when: a file that carries one puts it on.
                if (it.lastPlayed > s.lastPlayed) it.copy(starred = it.starred || s.starred)
                else it.copy(positionMs = s.positionMs, state = s.state, lastPlayed = s.lastPlayed, starred = s.starred)
            }
        }
    }

    private fun write(uri: Uri, text: String, okRes: Int) = lifecycleScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }.isSuccess
        }
        toast(getString(if (ok) okRes else R.string.write_failed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
        app.refreshIfStale()
        val activity = this
        setContent {
            val settings by app.prefs.settings.collectAsState()
            ReaderTheme(settings) {
                Bars()
                LaunchedEffect(Unit) {
                    while (true) {
                        controller?.let { c ->
                            ui.mediaId = c.currentMediaItem?.mediaId ?: ""
                            ui.playing = c.isPlaying
                            ui.positionMs = c.currentPosition
                            ui.durationMs = c.duration.takeIf { it > 0 } ?: 0L
                        }
                        delay(250)
                    }
                }
                when (nav.current) {
                    Screen.Home -> HomeScreen(nav, app, activity)
                    Screen.Search -> SearchScreen(nav, app, activity)
                    Screen.Player -> PlayerScreen(nav, app, activity)
                    Screen.Settings -> SettingsScreen(nav, app, activity)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        controllerFuture = f
        f.addListener({
            controller = runCatching { f.get() }.getOrNull()
            pendingPlay?.let { pendingPlay = null; play(it) }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    /** A feed address shared from a browser, and the widget's title opening the player. */
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { subscribe(it) }
            Intent.ACTION_VIEW -> intent.dataString?.let { subscribe(it) }
            ACTION_OPEN_PLAYER -> { nav.home(); if (app.store.last() != null) nav.push(Screen.Player) }
            else -> return
        }
        intent.action = null
    }

    // ---- subscriptions ----

    /** Add a feed from whatever was pasted or shared; the failure says why, in one line. */
    fun subscribe(raw: String) = lifecycleScope.launch {
        if (raw.isBlank()) return@launch
        busy = getString(R.string.adding_feed)
        val result = Refresher.add(this@MainActivity, app.store, raw)
        busy = ""
        result.onSuccess { feed ->
            app.prefs.setView(feed.id)
            nav.home()
            toast(getString(R.string.subscribed, feed.title))
        }.onFailure { e ->
            toast(if (e is Refresher.Refused) getString(e.reasonRes) else getString(R.string.feed_failed, (e.message ?: "").take(80)))
        }
    }

    fun unsubscribe(feedId: String) {
        if (app.prefs.settings.value.view == feedId) app.prefs.setView(com.freedomfighter.readerspodcasts.data.Prefs.VIEW_CHANNELS)
        app.store.episodesOf(feedId).forEach { if (ui.mediaId == it.id) stopPlayback() }
        app.store.removeFeed(feedId)
    }

    fun refreshAll() = app.refreshAll()

    fun refreshOne(feedId: String) = lifecycleScope.launch {
        val feed = app.store.feed(feedId) ?: return@launch
        Refresher.Live.total = 1
        Refresher.Live.running = 1
        Refresher.refresh(this@MainActivity, app.store, feed)
        Refresher.Live.running = 0
    }

    fun exportOpml() = runCatching { writeOpml.launch("abonnements.opml") }
    fun importOpml() = runCatching { readOpml.launch(arrayOf("*/*")) }
    fun exportSettings() = runCatching { writeBackup.launch("reglages.json") }
    fun importSettings() = runCatching { readBackup.launch(arrayOf("*/*")) }

    // ---- episodes ----

    fun download(e: Episode) = DownloadService.start(this, e.id, force = true)
    fun cancelDownload(e: Episode) = DownloadService.cancel(this, e.id)

    fun deleteFile(e: Episode) {
        if (ui.mediaId == e.id) stopPlayback()
        app.store.deleteFile(e.id)
    }

    /** The star, which is the one list the app never fills or empties by itself. */
    fun star(e: Episode, starred: Boolean) = app.store.updateEpisode(e.id) { it.copy(starred = starred) }

    fun markPlayed(e: Episode, played: Boolean) {
        if (played && ui.mediaId == e.id) stopPlayback()
        app.store.updateEpisode(e.id) {
            it.copy(state = if (played) State.PLAYED else State.NEW, positionMs = 0)
        }
        if (played && app.prefs.settings.value.deleteWhenPlayed) app.store.deleteFile(e.id)
    }

    /** The episode's own address, to send to someone or open in a browser. */
    fun share(e: Episode) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, e.title)
            .putExtra(Intent.EXTRA_TEXT, e.title + "\n" + e.mediaUrl)
        send.clipData = ClipData.newPlainText(e.title, e.mediaUrl)
        runCatching { startActivity(Intent.createChooser(send, e.title)) }
    }

    // ---- playback, through the session ----

    fun play(e: Episode) {
        val c = controller ?: run { pendingPlay = e; return }
        if (c.currentMediaItem?.mediaId == e.id && c.playbackState != Player.STATE_IDLE) {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play(); return
        }
        val start = if (e.durationMs > 0 && e.positionMs > e.durationMs - 3_000) 0L else e.positionMs
        c.setMediaItem(PlaybackService.mediaItem(e), start)
        c.setPlaybackSpeed(app.prefs.settings.value.speed)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.mediaItemCount == 0 -> app.store.last()?.let { play(it) }
            c.playbackState == Player.STATE_ENDED -> { c.seekTo(0); c.play() }
            c.playbackState == Player.STATE_IDLE -> { c.prepare(); c.play() }
            else -> c.play()
        }
    }

    fun seekBy(ms: Long) {
        val c = controller ?: return
        val max = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + ms).coerceIn(0, max))
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun setSpeed(f: Float) { app.prefs.setSpeed(f); controller?.setPlaybackSpeed(f) }
    fun stopPlayback() { controller?.sendCustomCommand(SessionCommand(PlaybackService.ACTION_STOP, Bundle.EMPTY), Bundle.EMPTY) }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    @Composable
    private fun Bars() {
        val view = LocalView.current
        val colors = LocalColors.current
        LaunchedEffect(colors.isDark) {
            val c = WindowInsetsControllerCompat(window, view)
            c.isAppearanceLightStatusBars = !colors.isDark
            c.isAppearanceLightNavigationBars = !colors.isDark
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.freedomfighter.readerspodcasts.OPEN_PLAYER"
    }
}
