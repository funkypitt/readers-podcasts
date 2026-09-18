package com.freedomfighter.readerspodcasts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerspodcasts.App
import com.freedomfighter.readerspodcasts.MainActivity
import com.freedomfighter.readerspodcasts.R
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Feed
import com.freedomfighter.readerspodcasts.data.FontChoice
import com.freedomfighter.readerspodcasts.data.Prefs
import com.freedomfighter.readerspodcasts.data.State
import com.freedomfighter.readerspodcasts.data.TextSize
import com.freedomfighter.readerspodcasts.data.clock
import com.freedomfighter.readerspodcasts.data.spoken
import com.freedomfighter.readerspodcasts.net.DownloadService
import com.freedomfighter.readerspodcasts.net.Refresher

sealed class Screen {
    data object Home : Screen()
    data object Feeds : Screen()
    data object Player : Screen()
    data object Settings : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
}

fun speedLabel(f: Float): String = (if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()) + "×"

/** A hairline, [fraction] of it in the foreground colour. */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier) {
    val colors = LocalColors.current
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(colors.rule, topLeft = Offset(0f, size.height / 3), size = Size(size.width, size.height / 3))
        drawRect(colors.fg, size = Size(size.width * fraction, size.height))
    }
}

// ---------------------------------------------------------------------------------------------
// The row of an episode. The kit's TextRow keeps titles to one line, which suits a file name and
// not a podcast title: here the title may take two, and the line underneath says where it stands.
// Everything else — paddings, sizes, the inverted state — is the kit's.
// ---------------------------------------------------------------------------------------------

@Composable
fun EpisodeRow(
    episode: Episode,
    app: App,
    activity: MainActivity,
    inverted: Boolean = false,
    /** Say which channel it comes from — in a list that mixes them, nothing else says so. */
    withFeed: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalColors.current
    val fg = if (inverted) colors.bg else colors.fg
    val dim = if (inverted) colors.bg.copy(alpha = 0.6f) else colors.dim
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (inverted) colors.fg else Color.Transparent)
            .pressable(onClick = onClick, onLongPress = onLongPress)
            .padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)
    ) {
        T(episode.title, color = fg, maxLines = 2)
        val status = episodeStatus(episode, app, activity, withFeed)
        if (status.isNotBlank()) Small(status, color = dim, maxLines = 1)
    }
}

/**
 * The line under a title, in one breath: when it came out, how long it is, and the one thing
 * worth knowing about it right now — that it is coming down, that it is here, that it was begun.
 */
@Composable
fun episodeStatus(episode: Episode, app: App, activity: MainActivity, withFeed: Boolean = false): String {
    val context = LocalContext.current
    val live = DownloadService.Live
    val playing = activity.ui.mediaId == episode.id
    val position = if (playing) activity.ui.positionMs else episode.positionMs
    val duration = if (playing && activity.ui.durationMs > 0) activity.ui.durationMs else episode.durationMs

    val channel = if (withFeed) app.store.feed(episode.feedId)?.title.orEmpty() else ""
    val when_ = relativeDate(context, episode.published)
    val length = if (duration > 0) spoken(context, duration) else ""
    val state = when {
        live.id == episode.id -> stringResource(R.string.downloading, live.percent)
        episode.id in live.waiting -> stringResource(R.string.download_waiting)
        live.errorId == episode.id && live.error.isNotBlank() -> live.error
        episode.state == State.PLAYED -> stringResource(R.string.played)
        position > 0 && duration > 0 -> stringResource(R.string.left_to_hear, spoken(context, duration - position))
        position > 0 -> stringResource(R.string.begun)
        episode.downloaded -> stringResource(R.string.on_the_phone)
        else -> ""
    }
    // One line, and it has to fit: the length is what one reads when there is nothing else to
    // say about the episode. As soon as there is — it is coming down, it is here, it was begun —
    // that is the useful thing, and a line that tried to hold both ended in an ellipsis.
    return listOf(channel, when_, state.ifBlank { length }).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Everything one can do with an episode: from a long press, or ⋯ in the player. */
@Composable
fun EpisodeMenu(episode: Episode, app: App, activity: MainActivity, nav: Nav, onDismiss: () -> Unit, inPlayer: Boolean = false) {
    val live = DownloadService.Live
    val busy = live.busy(episode.id)
    TextMenu(episode.title, buildList {
        if (!inPlayer) add(MenuItem(stringResource(R.string.play)) { activity.play(episode); nav.push(Screen.Player) })
        when {
            busy -> add(MenuItem(stringResource(R.string.stop_download)) { activity.cancelDownload(episode) })
            episode.downloaded -> add(MenuItem(stringResource(R.string.remove_from_phone), secondary = stringResource(R.string.kept_in_list)) { activity.deleteFile(episode) })
            else -> add(MenuItem(stringResource(R.string.download)) { activity.download(episode) })
        }
        if (episode.state == State.PLAYED) add(MenuItem(stringResource(R.string.mark_unplayed)) { activity.markPlayed(episode, false) })
        else add(MenuItem(stringResource(R.string.mark_played)) { activity.markPlayed(episode, true) })
        add(MenuItem(stringResource(R.string.share_episode)) { activity.share(episode) })
        app.store.feed(episode.feedId)?.let { feed ->
            add(MenuItem(stringResource(R.string.go_to_feed), secondary = feed.title) {
                app.prefs.setView(feed.id); nav.home()
            })
        }
        if (inPlayer) add(MenuItem(stringResource(R.string.stop)) { activity.stopPlayback(); nav.pop() })
    }, onDismiss = onDismiss)
}

// ---------------------------------------------------------------------------------------------
// Home: one list at a time — what is ready to hear, what is new, or one channel. The title says
// which, and tapping it goes to the channels, as in Reader's Tasks.
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(nav: Nav, app: App, activity: MainActivity) {
    val colors = LocalColors.current
    val settings by app.prefs.settings.collectAsState()
    val feeds by app.store.feeds.collectAsState()
    val all by app.store.episodes.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    val view = settings.view
    val feed = feeds.firstOrNull { it.id == view }
    // `all` is read above so that Compose knows this list depends on it: the store's queries
    // read the same state, and a download finishing has to redraw the row that was waiting.
    val episodes = remember(view, all, feeds) {
        when {
            view == Prefs.VIEW_NEW -> app.store.recent()
            feed != null -> app.store.episodesOf(feed.id)
            else -> app.store.queue()
        }
    }
    val title = when {
        view == Prefs.VIEW_NEW -> stringResource(R.string.view_new)
        feed != null -> feed.title
        else -> stringResource(R.string.view_queue)
    }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                title = "$title  ▾",
                onBack = null,
                trailing = "⋯",
                onTrailing = { menu = true },
                onTitle = { nav.push(Screen.Feeds) },
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                if (Refresher.Live.running > 0) {
                    item { Small(stringResource(R.string.refreshing), Modifier.padding(horizontal = rowPadH, vertical = 8.dp)) }
                }
                if (activity.busy.isNotBlank()) {
                    item { Small(activity.busy, Modifier.padding(horizontal = rowPadH, vertical = 8.dp)) }
                }
                if (episodes.isEmpty()) {
                    item {
                        val hint = when {
                            feeds.isEmpty() -> stringResource(R.string.empty_no_feeds)
                            view == Prefs.VIEW_QUEUE -> stringResource(R.string.empty_queue)
                            else -> stringResource(R.string.empty_feed)
                        }
                        Small(hint, Modifier.padding(horizontal = rowPadH, vertical = 16.dp), maxLines = 6)
                    }
                }
                items(episodes, key = { it.id }) { e ->
                    EpisodeRow(
                        e, app, activity, withFeed = feed == null,
                        onClick = { if (activity.ui.mediaId != e.id) activity.play(e); nav.push(Screen.Player) },
                        onLongPress = { rowMenu = e.id },
                    )
                }
            }
            Rule()
            val current = all.firstOrNull { it.id == activity.ui.mediaId }
            if (current != null) {
                EpisodeRow(
                    current, app, activity, inverted = true, withFeed = feed == null,
                    onClick = { nav.push(Screen.Player) },
                    onLongPress = { rowMenu = current.id },
                )
            }
            if (feeds.isEmpty()) TextRow(stringResource(R.string.add_feed)) { adding = true }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }

        if (menu) TextMenu(null, buildList {
            add(MenuItem(stringResource(R.string.refresh)) { activity.refreshAll() })
            add(MenuItem(stringResource(R.string.add_feed)) { adding = true })
            add(MenuItem(stringResource(R.string.feeds)) { nav.push(Screen.Feeds) })
        }, onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) },
        ))

        rowMenu?.let { id ->
            val e = all.firstOrNull { it.id == id }
            if (e == null) rowMenu = null else EpisodeMenu(e, app, activity, nav, onDismiss = { rowMenu = null })
        }

        if (adding) AddFeedPrompt(activity, onDismiss = { adding = false })
    }
}

/** The address of a feed, pasted. The keyboard's Done subscribes, as everywhere in Reader's. */
@Composable
fun AddFeedPrompt(activity: MainActivity, onDismiss: () -> Unit) {
    TextPrompt(
        title = stringResource(R.string.add_feed_hint),
        confirm = stringResource(R.string.subscribe),
        keyboard = androidx.compose.ui.text.input.KeyboardType.Uri,
        onDone = { activity.subscribe(it); onDismiss() },
        onCancel = onDismiss,
    )
}

// ---------------------------------------------------------------------------------------------
// The channels: the two standing lists, then the subscriptions. Choosing one takes you back to
// the home screen showing it — the list is a way through, not a place to stay.
// ---------------------------------------------------------------------------------------------

@Composable
fun FeedsScreen(nav: Nav, app: App, activity: MainActivity) {
    val typo = LocalTypo.current
    val tick = rememberTick()
    val settings by app.prefs.settings.collectAsState()
    val feeds by app.store.feeds.collectAsState()
    val all by app.store.episodes.collectAsState()
    var menuFor by remember { mutableStateOf<Feed?>(null) }
    var pageMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    BackHandler { nav.pop() }

    fun choose(view: String) { app.prefs.setView(view); nav.pop() }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.feeds), onBack = { nav.pop() }, trailing = "⋯", onTrailing = { pageMenu = true })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                if (activity.busy.isNotBlank()) {
                    item { Small(activity.busy, Modifier.padding(horizontal = rowPadH, vertical = 8.dp)) }
                }
                item {
                    val queue = remember(all) { app.store.queue().size }
                    TextRow(
                        stringResource(R.string.view_queue),
                        inverted = settings.view == Prefs.VIEW_QUEUE,
                        secondary = if (queue > 0) "$queue" else null,
                    ) { choose(Prefs.VIEW_QUEUE) }
                }
                item {
                    val fresh = remember(all) { app.store.recent().size }
                    TextRow(
                        stringResource(R.string.view_new),
                        inverted = settings.view == Prefs.VIEW_NEW,
                        secondary = if (fresh > 0) "$fresh" else null,
                    ) { choose(Prefs.VIEW_NEW) }
                }
                if (feeds.isNotEmpty()) item { Rule(Modifier.padding(vertical = 8.dp)) }
                items(feeds.sortedBy { it.title.lowercase() }, key = { it.id }) { f ->
                    val unplayed = remember(all, f.id) { app.store.unplayedCount(f.id) }
                    val secondary = when {
                        f.lastError.isNotBlank() -> f.lastError
                        unplayed > 0 -> "$unplayed"
                        else -> null
                    }
                    Box(Modifier.fillMaxWidth().pressable(onClick = { choose(f.id) }, onLongPress = { tick(); menuFor = f })) {
                        TextRow(f.title, inverted = settings.view == f.id, secondary = secondary)
                    }
                }
            }
            Rule()
            TextRow(stringResource(R.string.add_feed), size = typo.title) { adding = true }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }

        if (pageMenu) TextMenu(null, listOf(
            MenuItem(stringResource(R.string.refresh)) { activity.refreshAll() },
            MenuItem(stringResource(R.string.import_opml)) { activity.importOpml() },
            MenuItem(stringResource(R.string.export_opml), secondary = "abonnements.opml") { activity.exportOpml() },
        ), onDismiss = { pageMenu = false })

        menuFor?.let { f ->
            TextMenu(f.title, listOf(
                MenuItem(stringResource(R.string.refresh)) { activity.refreshOne(f.id) },
                MenuItem(
                    if (f.autoDownload) stringResource(R.string.auto_download_on) else stringResource(R.string.auto_download_off),
                    secondary = stringResource(R.string.auto_download),
                ) { app.store.updateFeed(f.id) { it.copy(autoDownload = !it.autoDownload) } },
                MenuItem(stringResource(R.string.unsubscribe), secondary = f.url) { activity.unsubscribe(f.id) },
            ), onDismiss = { menuFor = null })
        }

        if (adding) AddFeedPrompt(activity, onDismiss = { adding = false })
    }
}

// ---------------------------------------------------------------------------------------------
// The player: the time, large; a rule to tap; −5 · play · +10; the speed; then what the episode
// is about, because a podcast's notes are text and this app shows text.
// ---------------------------------------------------------------------------------------------

@Composable
fun PlayerScreen(nav: Nav, app: App, activity: MainActivity) {
    val typo = LocalTypo.current
    val tick = rememberTick()
    val all by app.store.episodes.collectAsState()
    val settings by app.prefs.settings.collectAsState()
    val ui = activity.ui
    val episode = all.firstOrNull { it.id == ui.mediaId } ?: all.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }
    var menu by remember { mutableStateOf(false) }
    BackHandler { nav.pop() }
    if (episode == null) { LaunchedEffect(Unit) { nav.pop() }; return }
    val current = ui.mediaId == episode.id
    val playing = current && ui.playing
    val pos = if (current) ui.positionMs else episode.positionMs
    val dur = if (current && ui.durationMs > 0) ui.durationMs else episode.durationMs
    val feed = app.store.feed(episode.feedId)
    val live = DownloadService.Live

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(episode.title, onBack = { nav.pop() }, trailing = "⋯", onTrailing = { menu = true })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                VSpace(24.dp)
                T(clock(pos), Modifier.padding(horizontal = rowPadH), size = typo.big, align = TextAlign.Start, maxLines = 1)
                Small(
                    listOfNotNull(
                        if (dur > 0) clock(dur) else null,
                        feed?.title,
                    ).joinToString(" · "),
                    Modifier.padding(horizontal = rowPadH), maxLines = 1,
                )
                var width by remember { mutableIntStateOf(1) }
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = rowPadH).height(44.dp)
                        .onSizeChanged { width = it.width }
                        .pointerInput(dur, current, episode.id) {
                            detectTapGestures { o: Offset ->
                                if (dur > 0) {
                                    val p = (o.x / width * dur).toLong().coerceIn(0, dur)
                                    if (current) activity.seekTo(p) else activity.play(episode.copy(positionMs = p))
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Progress(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f) }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Control(stringResource(R.string.back5), Modifier.weight(1f)) { tick(); if (current) activity.seekBy(-5_000) }
                    Control(if (playing) "❚❚" else "▶", Modifier.weight(1f), inverted = playing) { tick(); if (current) activity.toggle() else activity.play(episode) }
                    Control(stringResource(R.string.fwd10), Modifier.weight(1f)) { tick(); if (current) activity.seekBy(10_000) }
                }
                TextRow(speedLabel(settings.speed), secondary = stringResource(R.string.speed), size = typo.title) {
                    val i = Prefs.SPEEDS.indexOf(settings.speed).let { if (it < 0) 1 else it }
                    activity.setSpeed(Prefs.SPEEDS[(i + 1) % Prefs.SPEEDS.size])
                }
                Rule(Modifier.padding(vertical = 8.dp))
                when {
                    live.id == episode.id -> TextRow(stringResource(R.string.downloading, live.percent), secondary = stringResource(R.string.stop_download), size = typo.title) { activity.cancelDownload(episode) }
                    episode.id in live.waiting -> TextRow(stringResource(R.string.download_waiting), secondary = stringResource(R.string.stop_download), size = typo.title) { activity.cancelDownload(episode) }
                    episode.downloaded -> TextRow(stringResource(R.string.remove_from_phone), secondary = stringResource(R.string.on_the_phone), size = typo.title) { activity.deleteFile(episode) }
                    else -> TextRow(stringResource(R.string.download), secondary = stringResource(R.string.streaming_hint), size = typo.title) { activity.download(episode) }
                }
                if (live.errorId == episode.id && live.error.isNotBlank() && live.id != episode.id) {
                    Small(live.error, Modifier.padding(horizontal = rowPadH, vertical = 6.dp), maxLines = 3)
                }
                if (episode.description.isNotBlank()) {
                    Rule(Modifier.padding(vertical = 8.dp))
                    T(
                        episode.description, Modifier.padding(horizontal = rowPadH, vertical = 8.dp),
                        size = typo.title, align = TextAlign.Start, lineHeightMul = 1.4f,
                    )
                }
                VSpace(16.dp)
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) EpisodeMenu(episode, app, activity, nav, onDismiss = { menu = false }, inPlayer = true)
    }
}

@Composable
private fun Control(label: String, modifier: Modifier, inverted: Boolean = false, onClick: () -> Unit) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    Box(
        modifier.height(76.dp).background(if (inverted) colors.fg else Color.Transparent).noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        T(label, size = typo.title, color = if (inverted) colors.bg else colors.fg, align = TextAlign.Center, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Settings: what the app does by itself, the two files it exchanges, and the look.
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(nav: Nav, app: App, activity: MainActivity) {
    val s by app.prefs.settings.collectAsState()
    val colors = LocalColors.current
    val typo = LocalTypo.current
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                // One line per setting, as in Reader's Tasks: three rows all reading "on" above
                // their label is a column one has to decipher rather than read.
                Setting(R.string.wifi_only, onOff(s.wifiOnly)) { app.prefs.setWifiOnly(!s.wifiOnly) }
                Setting(R.string.auto_refresh, onOff(s.autoRefresh)) { app.prefs.setAutoRefresh(!s.autoRefresh) }
                Setting(R.string.delete_when_played, onOff(s.deleteWhenPlayed)) { app.prefs.setDeleteWhenPlayed(!s.deleteWhenPlayed) }
                Rule(Modifier.padding(vertical = 8.dp))
                Small(stringResource(R.string.exchange_hint), Modifier.padding(horizontal = rowPadH).padding(bottom = 6.dp), maxLines = 6)
                TextRow(stringResource(R.string.export_opml), secondary = "abonnements.opml", size = typo.title) { activity.exportOpml() }
                TextRow(stringResource(R.string.import_opml), size = typo.title) { activity.importOpml() }
                TextRow(stringResource(R.string.export_settings), secondary = "reglages.json", size = typo.title) { activity.exportSettings() }
                TextRow(stringResource(R.string.import_settings), size = typo.title) { activity.importSettings() }
                Rule(Modifier.padding(vertical = 8.dp))
                Setting(R.string.colours, if (colors.isDark) stringResource(R.string.theme_dark) else stringResource(R.string.theme_light)) { app.prefs.toggleTheme(colors.isDark) }
                Setting(R.string.text_size, when (s.textSize) { TextSize.SMALL -> "S"; TextSize.MEDIUM -> "M"; TextSize.LARGE -> "L" }) {
                    app.prefs.setTextSize(when (s.textSize) { TextSize.SMALL -> TextSize.MEDIUM; TextSize.MEDIUM -> TextSize.LARGE; TextSize.LARGE -> TextSize.SMALL })
                }
                Setting(R.string.font, when (s.font) { FontChoice.SANS -> "sans-serif"; FontChoice.SERIF -> "serif"; FontChoice.MONO -> "mono" }) {
                    app.prefs.setFont(when (s.font) { FontChoice.SANS -> FontChoice.SERIF; FontChoice.SERIF -> FontChoice.MONO; FontChoice.MONO -> FontChoice.SANS })
                }
                Setting(R.string.haptics, onOff(s.haptics)) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(
                    stringResource(R.string.app_name) + "  " + com.freedomfighter.readerspodcasts.BuildConfig.VERSION_NAME,
                    secondary = stringResource(R.string.about), size = typo.title,
                ) { }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun onOff(v: Boolean) = stringResource(if (v) R.string.on else R.string.off)

/** `label : value` on one line — the punctuation is the language's, hence a format string. */
@Composable
private fun Setting(label: Int, value: String, onClick: () -> Unit) =
    TextRow(stringResource(R.string.setting_line, stringResource(label), value), size = LocalTypo.current.title, onClick = onClick)
