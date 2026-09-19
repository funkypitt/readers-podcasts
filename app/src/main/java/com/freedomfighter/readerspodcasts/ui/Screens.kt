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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerspodcasts.App
import com.freedomfighter.readerspodcasts.MainActivity
import com.freedomfighter.readerspodcasts.R
import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.Feed
import com.freedomfighter.readerspodcasts.data.FontChoice
import com.freedomfighter.readerspodcasts.data.Chapters
import com.freedomfighter.readerspodcasts.data.Kind
import com.freedomfighter.readerspodcasts.net.Extractor
import com.freedomfighter.readerspodcasts.data.Prefs
import com.freedomfighter.readerspodcasts.data.feedLanguage
import com.freedomfighter.readerspodcasts.data.State
import com.freedomfighter.readerspodcasts.data.TextSize
import com.freedomfighter.readerspodcasts.data.clock
import com.freedomfighter.readerspodcasts.data.spoken
import com.freedomfighter.readerspodcasts.net.DownloadService
import com.freedomfighter.readerspodcasts.net.Refresher
import com.freedomfighter.readerspodcasts.TranscribeService
import com.freedomfighter.readers.speech.translate.TranslateModel
import com.freedomfighter.readers.speech.whisper.Models
import com.freedomfighter.readers.speech.whisper.Prompts

sealed class Screen {
    data object Home : Screen()
    data object Search : Screen()

    /**
     * The player, for [id] — the episode one tapped, which is not always the one the audio
     * session holds. Showing what was playing instead of what was asked for was baffling:
     * tapping a YouTube episode that had yet to come down opened someone else's podcast.
     */
    data class Player(val id: String? = null) : Screen()

    /** What was said, read while it is said. */
    data class Text(val id: String) : Screen()
    data object Settings : Screen()
}

class Nav {
    /** What was being searched for, so that coming back from an episode finds the results again. */
    var searchQuery by mutableStateOf("")
    /** Where each list of the home screen was left: a hundred and forty channels are not scrolled twice. */
    private val lists = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    fun listState(view: String) = lists.getOrPut(view) { androidx.compose.foundation.lazy.LazyListState() }

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
}

const val STAR = "★"

fun speedLabel(f: Float): String = (if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()) + "×"

/** The name of one of the three standing views. */
@Composable
fun viewLabel(view: String): String = stringResource(
    when (view) {
        Prefs.VIEW_EPISODES -> R.string.view_episodes
        Prefs.VIEW_FAVOURITES -> R.string.view_favourites
        Prefs.VIEW_DOWNLOADED -> R.string.view_downloaded
        else -> R.string.view_channels
    }
)

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
 * The line under a title, in one breath: the star if it has one, the channel when the list mixes
 * them, when it came out, and the one thing worth knowing about it right now.
 */
@Composable
fun episodeStatus(episode: Episode, app: App, activity: MainActivity, withFeed: Boolean = false): String {
    val context = LocalContext.current
    val live = DownloadService.Live
    val playing = activity.ui.mediaId == episode.id
    val position = if (playing) activity.ui.positionMs else episode.positionMs
    val duration = if (playing && activity.ui.durationMs > 0) activity.ui.durationMs else episode.durationMs

    val star = if (episode.starred) STAR else ""
    val channel = if (withFeed) app.store.feed(episode.feedId)?.title.orEmpty() else ""
    val when_ = relativeDate(context, episode.published)
    val length = if (duration > 0) spoken(context, duration) else ""
    val state = when {
        live.id == episode.id && live.phase.isNotBlank() -> live.phase
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
    return listOf(star, channel, when_, state.ifBlank { length }).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Everything one can do with an episode: from a long press, or ⋯ in the player. */
@Composable
fun EpisodeMenu(episode: Episode, app: App, activity: MainActivity, nav: Nav, onDismiss: () -> Unit, inPlayer: Boolean = false) {
    val live = DownloadService.Live
    val busy = live.busy(episode.id)
    TextMenu(episode.title, buildList {
        if (!inPlayer) add(MenuItem(stringResource(R.string.play)) { activity.open(episode, nav); activity.play(episode) })
        when {
            busy -> add(MenuItem(stringResource(R.string.stop_download)) { activity.cancelDownload(episode) })
            episode.downloaded -> add(MenuItem(stringResource(R.string.remove_from_phone), secondary = stringResource(R.string.kept_in_list)) { activity.deleteFile(episode) })
            else -> add(MenuItem(stringResource(R.string.download)) { activity.download(episode) })
        }
        add(MenuItem(stringResource(if (episode.starred) R.string.unstar else R.string.star)) { activity.star(episode, !episode.starred) })
        if (episode.state == State.PLAYED) add(MenuItem(stringResource(R.string.mark_unplayed)) { activity.markPlayed(episode, false) })
        else add(MenuItem(stringResource(R.string.mark_played)) { activity.markPlayed(episode, true) })
        add(MenuItem(stringResource(R.string.share_episode)) { activity.share(episode) })
        // Not when one is already reading that channel: the row would lead where one stands.
        val current = app.prefs.settings.value.view
        app.store.feed(episode.feedId)?.takeIf { it.id != current }?.let { feed ->
            add(MenuItem(stringResource(R.string.go_to_feed), secondary = feed.title) {
                app.prefs.setView(feed.id); nav.home()
            })
        }
        if (inPlayer) add(MenuItem(stringResource(R.string.stop)) { activity.stopPlayback(); nav.pop() })
    }, onDismiss = onDismiss)
}

/** What a channel offers on a long press. */
@Composable
fun FeedMenu(feed: Feed, app: App, activity: MainActivity, onDismiss: () -> Unit) {
    TextMenu(feed.title, listOf(
        MenuItem(stringResource(R.string.refresh)) { activity.refreshOne(feed.id) },
        MenuItem(
            if (feed.autoDownload) stringResource(R.string.auto_download_on) else stringResource(R.string.auto_download_off),
            secondary = stringResource(R.string.auto_download),
        ) { app.store.updateFeed(feed.id) { it.copy(autoDownload = !it.autoDownload) } },
        MenuItem(stringResource(R.string.unsubscribe), secondary = feed.url) { activity.unsubscribe(feed.id) },
    ), onDismiss = onDismiss)
}

/** The row of a channel: when it last published, and how many are unheard. */
@Composable
fun FeedRow(feed: Feed, app: App, latest: Long, unheard: Int, onClick: () -> Unit, onLongPress: () -> Unit) {
    val second = listOf(
        if (feed.lastError.isNotBlank()) feed.lastError else relativeDate(LocalContext.current, latest),
        if (unheard > 0) "$unheard" else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
    Box(Modifier.fillMaxWidth().pressable(onClick = onClick, onLongPress = onLongPress)) {
        TextRow(feed.title, secondary = second.ifBlank { null })
    }
}

/** An address worth offering, or "" — the clipboard usually holds something else entirely. */
fun clipboardUrl(text: String?): String {
    val s = text?.trim().orEmpty()
    val looksRight = s.length in 8..2000 && ' ' !in s &&
        listOf("http://", "https://", "feed://", "podcast://", "pcast://").any { s.startsWith(it, true) }
    return if (looksRight) s else ""
}

/**
 * Pull the list down to refresh. Compose has no such thing outside Material, and Material would
 * bring a spinning wheel into an app that has no icons: here what follows the finger is a line
 * of text, and the list itself stays where it was until the finger is lifted.
 */
@Composable
fun PullToRefresh(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scrolling back up takes the pull away first, so the list does not jump.
                if (source == NestedScrollSource.Drag && available.y < 0f && pull > 0f) {
                    val used = minOf(pull, -available.y)
                    pull -= used
                    return Offset(0f, -used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0f) {
                    pull = (pull + available.y * 0.6f).coerceAtMost(threshold * 2f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull >= threshold) onRefresh()
                pull = 0f
                return Velocity.Zero
            }
        }
    }
    Column(modifier) {
        if (pull > 0f) {
            Box(
                Modifier.fillMaxWidth().height(with(density) { pull.toDp() }),
                contentAlignment = Alignment.Center,
            ) {
                Small(
                    stringResource(if (pull >= threshold) R.string.release_to_refresh else R.string.pull_to_refresh),
                    maxLines = 1, align = TextAlign.Center,
                )
            }
        }
        content(Modifier.nestedScroll(connection))
    }
}

// ---------------------------------------------------------------------------------------------
// Home: one list at a time — the channels, the episodes, the favourites, or a single channel.
// The title says which and opens the choice; ↻ and + sit beside ⋯; pulling the list down
// refreshes, with a line of text where other apps spin a wheel.
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(nav: Nav, app: App, activity: MainActivity) {
    val colors = LocalColors.current
    val settings by app.prefs.settings.collectAsState()
    val feeds by app.store.feeds.collectAsState()
    val all by app.store.episodes.collectAsState()
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var views by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    var feedMenu by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }

    // Whatever is stored, the screen shows something: a feed that is gone, or a name from a
    // version that knew other lists, falls back to the channels rather than to an empty page.
    val feed = feeds.firstOrNull { it.id == settings.view }
    val view = Prefs.viewOrChannels(settings.view, feed != null)
    // `all` and `feeds` are read above so that Compose knows these lists depend on them: the
    // store's queries read the same state, and a download finishing has to redraw its row.
    val episodes = remember(view, all, feeds) {
        when {
            feed != null -> app.store.episodesOf(feed.id)
            view == Prefs.VIEW_FAVOURITES -> app.store.favourites()
            view == Prefs.VIEW_DOWNLOADED -> app.store.downloaded()
            view == Prefs.VIEW_EPISODES -> app.store.recent()
            else -> emptyList()
        }
    }
    val channels = remember(all, feeds) { app.store.channels() }
    val showingChannels = feed == null && view == Prefs.VIEW_CHANNELS

    // Back from inside a channel returns to the channels, not out of the app.
    BackHandler(enabled = feed != null) { app.prefs.setView(Prefs.VIEW_CHANNELS) }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                title = feed?.title ?: viewLabel(view),
                // Inside a channel there is somewhere to go back to, and an arrow that says so.
                onBack = if (feed != null) ({ app.prefs.setView(Prefs.VIEW_CHANNELS) }) else null,
                trailing = "⋯",
                onTrailing = { menu = true },
                onTitle = { views = true },
                // Inside a channel the bar is for that channel: ↻ fetches it alone, and adding
                // another feed belongs to the lists — which leaves the name room to be read.
                actions = if (feed != null) listOf("↻" to { activity.refreshOne(feed.id); Unit })
                else listOf(
                    "↻" to { activity.refreshAll() },
                    "+" to { adding = clipboardUrl(clipboard.getText()?.text) },
                ),
            )
            PullToRefresh(onRefresh = { if (feed != null) activity.refreshOne(feed.id) else activity.refreshAll() }, modifier = Modifier.weight(1f)) { pulled ->
                LazyColumn(pulled.fillMaxSize(), state = nav.listState(feed?.id ?: view), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                    if (Refresher.Live.running > 0) {
                        item {
                            Small(
                                stringResource(
                                    R.string.refreshing_n,
                                    Refresher.Live.total - Refresher.Live.running + 1, Refresher.Live.total,
                                ),
                                Modifier.padding(horizontal = rowPadH, vertical = 8.dp),
                            )
                        }
                    }
                    if (activity.busy.isNotBlank()) {
                        item { Small(activity.busy, Modifier.padding(horizontal = rowPadH, vertical = 8.dp)) }
                    }
                    if (showingChannels) {
                        if (channels.isEmpty()) item { Hint(stringResource(R.string.empty_no_feeds)) }
                        items(channels, key = { it.id }) { f ->
                            val latest = remember(all, f.id) { app.store.episodesOf(f.id).firstOrNull()?.published ?: 0L }
                            val unheard = remember(all, f.id) { app.store.unplayedCount(f.id) }
                            FeedRow(f, app, latest, unheard,
                                onClick = { app.prefs.setView(f.id) },
                                onLongPress = { feedMenu = f.id })
                        }
                    } else {
                        if (episodes.isEmpty()) {
                            item {
                                Hint(
                                    when {
                                        feeds.isEmpty() -> stringResource(R.string.empty_no_feeds)
                                        view == Prefs.VIEW_FAVOURITES -> stringResource(R.string.empty_favourites)
                                        view == Prefs.VIEW_DOWNLOADED -> stringResource(R.string.empty_downloaded)
                                        else -> stringResource(R.string.empty_feed)
                                    }
                                )
                            }
                        }
                        items(episodes, key = { it.id }) { e ->
                            EpisodeRow(
                                e, app, activity, withFeed = feed == null,
                                onClick = { activity.open(e, nav) },
                                onLongPress = { rowMenu = e.id },
                            )
                        }
                        // A YouTube feed carries its latest fifteen and nothing else; the rest of
                        // the channel is only reachable through its own page, which yt-dlp reads.
                        if (feed != null && feed.kind == Kind.YOUTUBE && Extractor.AVAILABLE) {
                            item {
                                Rule()
                                TextRow(
                                    stringResource(R.string.load_older),
                                    secondary = stringResource(R.string.load_older_hint),
                                    size = LocalTypo.current.title,
                                ) { activity.loadOlder(feed) }
                            }
                        }
                    }
                }
            }
            Rule()
            val current = all.firstOrNull { it.id == activity.ui.mediaId }
            if (current != null) {
                EpisodeRow(
                    current, app, activity, inverted = true, withFeed = feed == null,
                    onClick = { nav.push(Screen.Player(current.id)) },
                    onLongPress = { rowMenu = current.id },
                )
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }

        // The tick sits on the label, not under it: on its own line it read as a second setting.
        if (views) TextMenu(null, Prefs.VIEWS.map { v ->
            MenuItem(viewLabel(v) + if (v == view) "  ✓" else "") { app.prefs.setView(v) }
        }, onDismiss = { views = false })

        if (menu) TextMenu(null, buildList {
            add(MenuItem(stringResource(R.string.search)) { nav.searchQuery = ""; nav.push(Screen.Search) })
            add(MenuItem(stringResource(R.string.refresh)) { activity.refreshAll() })
            add(MenuItem(stringResource(R.string.add_feed)) { adding = clipboardUrl(clipboard.getText()?.text) })
            add(MenuItem(stringResource(R.string.import_opml)) { activity.importOpml() })
            add(MenuItem(stringResource(R.string.export_opml), secondary = "abonnements.opml") { activity.exportOpml() })
        }, onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) },
        ))

        rowMenu?.let { id ->
            val e = all.firstOrNull { it.id == id }
            if (e == null) rowMenu = null else EpisodeMenu(e, app, activity, nav, onDismiss = { rowMenu = null })
        }

        feedMenu?.let { id ->
            val f = feeds.firstOrNull { it.id == id }
            if (f == null) feedMenu = null else FeedMenu(f, app, activity, onDismiss = { feedMenu = null })
        }

        adding?.let { initial ->
            // The clipboard holds an address often enough that pasting it by hand is a chore;
            // when it does, it is offered selected, so the first key replaces it and nothing
            // has to be cleared by holding backspace.
            TextPrompt(
                title = stringResource(R.string.add_feed_hint),
                initial = initial,
                confirm = stringResource(R.string.subscribe),
                keyboard = androidx.compose.ui.text.input.KeyboardType.Uri,
                selectAll = true,
                onDone = { activity.subscribe(it); adding = null },
                onCancel = { adding = null },
            )
        }
    }
}

@Composable
private fun Hint(text: String) = Small(text, Modifier.padding(horizontal = rowPadH, vertical = 16.dp), maxLines = 6)

// ---------------------------------------------------------------------------------------------
// Search: over what is already here — the channels one follows and their episodes. Nothing is
// asked of anyone else's directory, which is also why it works with the connection off.
// ---------------------------------------------------------------------------------------------

@Composable
fun SearchScreen(nav: Nav, app: App, activity: MainActivity) {
    val feeds by app.store.feeds.collectAsState()
    val all by app.store.episodes.collectAsState()
    var query by nav::searchQuery
    var rowMenu by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    BackHandler { nav.pop() }
    // The keyboard comes up for a new search, not over results one is coming back to.
    LaunchedEffect(Unit) { if (query.isBlank()) focus.requestFocus() }

    val needle = query.trim().lowercase()
    val channels = remember(needle, feeds) {
        if (needle.length < 2) emptyList() else feeds.filter { it.title.lowercase().contains(needle) }.sortedBy { it.title.lowercase() }
    }
    val episodes = remember(needle, all) {
        if (needle.length < 2) emptyList()
        else all.filter { it.title.lowercase().contains(needle) }.sortedByDescending { it.published }.take(300)
    }

    Page {
        Column(Modifier.fillMaxSize().imePadding()) {
            ScreenTitle(stringResource(R.string.search), onBack = { nav.pop() })
            ReaderTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 12.dp).focusRequester(focus),
                placeholder = stringResource(R.string.search_hint),
                imeAction = ImeAction.Search,
            )
            Rule()
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                if (needle.length >= 2 && channels.isEmpty() && episodes.isEmpty()) {
                    item { Hint(stringResource(R.string.search_nothing)) }
                }
                items(channels, key = { "f" + it.id }) { f ->
                    val unheard = remember(all, f.id) { app.store.unplayedCount(f.id) }
                    val latest = remember(all, f.id) { app.store.episodesOf(f.id).firstOrNull()?.published ?: 0L }
                    FeedRow(f, app, latest, unheard,
                        onClick = { app.prefs.setView(f.id); nav.home() },
                        onLongPress = { app.prefs.setView(f.id); nav.home() })
                }
                if (channels.isNotEmpty() && episodes.isNotEmpty()) item { Rule(Modifier.padding(vertical = 6.dp)) }
                items(episodes, key = { "e" + it.id }) { e ->
                    EpisodeRow(
                        e, app, activity, withFeed = true,
                        onClick = { activity.open(e, nav) },
                        onLongPress = { rowMenu = e.id },
                    )
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        rowMenu?.let { id ->
            val e = all.firstOrNull { it.id == id }
            if (e == null) rowMenu = null else EpisodeMenu(e, app, activity, nav, onDismiss = { rowMenu = null })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The player: the time, large; a rule to tap; −5 · play · +10; the speed; then what the episode
// is about, because a podcast's notes are text and this app shows text.
// ---------------------------------------------------------------------------------------------

@Composable
fun PlayerScreen(nav: Nav, app: App, activity: MainActivity, wanted: String?) {
    val context = LocalContext.current
    val typo = LocalTypo.current
    val tick = rememberTick()
    val all by app.store.episodes.collectAsState()
    val settings by app.prefs.settings.collectAsState()
    val ui = activity.ui
    // What was asked for first; only then what happens to be playing, and only then the last
    // thing played — which is all this screen had to go on before.
    val episode = all.firstOrNull { it.id == wanted }
        ?: all.firstOrNull { it.id == ui.mediaId }
        ?: all.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }
    var menu by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
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
            // The bar names the channel; the episode's own title is given room below, in full.
            // It used to sit in the bar, cut after thirty letters, and could be read nowhere.
            ScreenTitle(
                feed?.title.orEmpty(),
                onBack = { nav.pop() },
                trailing = "⋯",
                onTrailing = { menu = true },
                actions = listOf((if (episode.starred) STAR else "☆") to { activity.star(episode, !episode.starred) }),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                VSpace(14.dp)
                T(episode.title, Modifier.padding(horizontal = rowPadH), align = TextAlign.Start, maxLines = 5)
                Small(
                    listOf(relativeDate(context, episode.published), if (dur > 0) spoken(context, dur) else "")
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    Modifier.padding(horizontal = rowPadH).padding(top = 2.dp), maxLines = 1,
                )
                VSpace(10.dp)
                // Level with the clock, the two things one reaches for while listening: the speed,
                // which turns on a tap, and the text when there is one to read along.
                Row(Modifier.fillMaxWidth().padding(horizontal = rowPadH), verticalAlignment = Alignment.CenterVertically) {
                    T(clock(pos), Modifier.weight(1f), size = typo.big, align = TextAlign.Start, maxLines = 1)
                    T(
                        speedLabel(settings.speed),
                        Modifier.noRippleClickable(onClick = {
                            tick()
                            val i = Prefs.SPEEDS.indexOf(settings.speed).let { if (it < 0) 1 else it }
                            activity.setSpeed(Prefs.SPEEDS[(i + 1) % Prefs.SPEEDS.size])
                        }).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                        size = typo.title, align = TextAlign.End, maxLines = 1,
                    )
                    if (episode.transcript) {
                        T(
                            stringResource(R.string.read_text),
                            Modifier.noRippleClickable(onClick = { nav.push(Screen.Text(episode.id)) })
                                .padding(start = 24.dp, top = 12.dp, bottom = 12.dp),
                            size = typo.title, align = TextAlign.End, maxLines = 1,
                        )
                    }
                }
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
                Row(Modifier.fillMaxWidth()) {
                    Control(stringResource(R.string.back5), Modifier.weight(1f)) { tick(); if (current) activity.seekBy(-5_000) }
                    Control(if (playing) "❚❚" else "▶", Modifier.weight(1f), inverted = playing) { tick(); if (current) activity.toggle() else activity.play(episode) }
                    Control(stringResource(R.string.fwd10), Modifier.weight(1f)) { tick(); if (current) activity.seekBy(10_000) }
                }
                // Chapters, when the description holds a list of them: the row says where one is
                // and opens the whole table, and a chapter chosen sends the sound to its start.
                val chapters = remember(episode.description, dur) { Chapters.parse(episode.description, dur) }
                if (chapters.isNotEmpty()) {
                    val here = Chapters.at(chapters, pos)
                    TextRow(
                        here?.title ?: stringResource(R.string.chapters),
                        secondary = stringResource(R.string.chapters_of, chapters.size),
                        size = typo.title,
                    ) { showChapters = true }
                }
                // What the episode is about comes before what can be done with it: one opens an
                // episode to find out whether to listen, and the notes used to start under the
                // fold, below five rows of actions. Long notes are folded, not the other way round.
                if (episode.description.isNotBlank()) {
                    Rule(Modifier.padding(vertical = 8.dp))
                    var unfolded by remember(episode.id) { mutableStateOf(false) }
                    var overflows by remember(episode.id) { mutableStateOf(false) }
                    LinkedText(
                        episode.description, Modifier.padding(horizontal = rowPadH, vertical = 6.dp),
                        size = typo.title,
                        maxLines = if (unfolded) Int.MAX_VALUE else 8,
                        onOverflow = { if (!unfolded) overflows = it },
                        onCopied = { activity.toast(context.getString(R.string.copied)) },
                    )
                    if (overflows && !unfolded) {
                        Small(
                            stringResource(R.string.read_on),
                            Modifier.fillMaxWidth().noRippleClickable(onClick = { unfolded = true })
                                .padding(horizontal = rowPadH, vertical = 10.dp),
                            maxLines = 1,
                        )
                    }
                }
                Rule(Modifier.padding(vertical = 8.dp))
                when {
                    live.id == episode.id -> TextRow(
                        live.phase.ifBlank { stringResource(R.string.downloading, live.percent) },
                        secondary = stringResource(R.string.stop_download), size = typo.title,
                    ) { activity.cancelDownload(episode) }
                    episode.id in live.waiting -> TextRow(stringResource(R.string.download_waiting), secondary = stringResource(R.string.stop_download), size = typo.title) { activity.cancelDownload(episode) }
                    episode.downloaded -> TextRow(stringResource(R.string.remove_from_phone), secondary = stringResource(R.string.on_the_phone), size = typo.title) { activity.deleteFile(episode) }
                    else -> TextRow(stringResource(R.string.download), secondary = stringResource(R.string.streaming_hint), size = typo.title) { activity.download(episode) }
                }
                TextRows(episode, app, activity, nav)
                // The whole of it, wrapped: an error from yt-dlp says what is wrong in a sentence,
                // and a row that cut it to one line said nothing anyone could act on.
                if (live.errorId == episode.id && live.error.isNotBlank() && live.id != episode.id) {
                    Small(live.error, Modifier.padding(horizontal = rowPadH, vertical = 8.dp), maxLines = 8)
                }
                VSpace(16.dp)
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) EpisodeMenu(episode, app, activity, nav, onDismiss = { menu = false }, inPlayer = true)
        if (showChapters) {
            val chapters = Chapters.parse(episode.description, dur)
            val here = Chapters.at(chapters, pos)
            TextMenu(stringResource(R.string.chapters), chapters.map { c ->
                MenuItem(c.title, secondary = clock(c.startMs) + (if (c == here) "  ✓" else "")) {
                    activity.seekOrPlay(episode, c.startMs)
                }
            }, onDismiss = { showChapters = false })
        }
        activity.transcribing?.let { id ->
            val asked = all.firstOrNull { it.id == id }
            if (asked == null) activity.closeTranscribeSheet()
            else TranscribeSheet(asked, activity, onDismiss = { activity.closeTranscribeSheet() })
        }
    }
}

/**
 * What can be done with the words of an episode: write them down, read them along with the
 * sound, put them into the language one reads in. All of it happens on the telephone, which is
 * why each row says how far it has got rather than pretending to be instant.
 */
@Composable
fun TextRows(episode: Episode, app: App, activity: MainActivity, nav: Nav) {
    val context = LocalContext.current
    val typo = LocalTypo.current
    val live = TranscribeService.Live
    val mine = live.id == episode.id
    val reading = Prefs.deviceLanguage()
    // A text already written stays readable whatever else is going on: hiding it while its
    // translation was being worked out took away the very thing one had waited for.
    when {
        mine -> TextRow(
            TranscribeService.phaseLabel(context, live.phase, live.percent),
            secondary = stringResource(R.string.stop_transcription), size = typo.title,
        ) { activity.cancelTranscription() }

        episode.id in live.waiting -> TextRow(
            stringResource(R.string.phase_waiting),
            secondary = stringResource(R.string.stop_transcription), size = typo.title,
        ) { activity.cancelTranscription() }

        episode.transcript -> TranslateRow(episode, activity, reading)

        !episode.downloaded -> TextRow(
            stringResource(R.string.transcribe),
            secondary = stringResource(R.string.transcribe_needs_file), size = typo.title,
        ) { activity.download(episode) }

        else -> TextRow(
            stringResource(R.string.transcribe),
            secondary = stringResource(R.string.transcribe_hint), size = typo.title,
        ) { activity.askTranscribe(episode) }
    }
    if (live.errorId == episode.id && live.error.isNotBlank() && !mine) {
        Small(live.error, Modifier.padding(horizontal = rowPadH, vertical = 8.dp), maxLines = 8)
    }
}

/**
 * Translating is only offered when it would change anything — a talk already in the language one
 * reads needs none — and the phone that cannot hold the model is told so rather than left to
 * discover it when the process dies.
 */
@Composable
private fun TranslateRow(episode: Episode, activity: MainActivity, reading: String) {
    val context = LocalContext.current
    val typo = LocalTypo.current
    if (episode.transcriptLanguage.isNotBlank() && episode.transcriptLanguage == reading) return
    if (episode.translation == reading) return
    val roomy = remember { TranslateModel.phoneCanHoldIt(context) }
    val here = remember { TranslateModel.isDownloaded(context) }
    val secondary = when {
        !roomy -> stringResource(R.string.translate_needs_memory, TranslateModel.phoneMemoryGb(context))
        here -> stringResource(R.string.translate_hint)
        else -> "${TranslateModel.MB} MB · " + stringResource(R.string.model_not_yet)
    }
    TextRow(
        stringResource(R.string.translate_into, languageName(reading)),
        secondary = secondary, size = typo.title,
        onClick = if (!roomy) null else ({ activity.translate(episode, reading) }),
    )
}

/**
 * Asked before every transcription: the language spoken, the phone's by default, and the
 * quality. High is Whisper large-v3-turbo — better punctuation, much slower on a telephone.
 */
/** What is advised here: whole talks, so the model that keeps the wait to something bearable. */
private val RECOMMENDED = Models.NORMAL

@Composable
fun TranscribeSheet(episode: Episode, activity: MainActivity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    // No language until one is chosen. The phone's own language as a default was twice taken for
    // granted over a talk that was in another one, and an hour of writing down is too long a wait
    // to find that out at the end. So the list opens by itself and nothing runs before an answer.
    // What this channel was last written down in is ticked and put first — a suggestion one taps,
    // not a choice made on one's behalf.
    val suggested = remember(episode.feedId) {
        feedLanguage(activity.app.store.episodes.value, episode.feedId)
    }
    var language by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf(Models.DEFAULT) }
    var picking by remember { mutableStateOf(true) }
    val downloading by Models.downloading.collectAsState()
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(colors.bg.copy(alpha = 0.6f)).noRippleClickable(onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(colors.bg).noRippleClickable { }
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Rule(color = colors.fg)
            Small(episode.title, Modifier.padding(horizontal = rowPadH).padding(top = 14.dp, bottom = 2.dp), maxLines = 1)
            TextRow(
                language?.let { spokenLanguage(it) } ?: stringResource(R.string.language_to_choose),
                secondary = stringResource(R.string.language), size = typo.title,
            ) { picking = true }
            Rule(Modifier.padding(vertical = 4.dp))
            Models.ALL.forEach { m ->
                val state = when {
                    Models.isDownloaded(context, m) -> ""
                    downloading >= 0 -> " · " + stringResource(R.string.phase_model, downloading)
                    else -> " · " + stringResource(R.string.model_not_yet)
                }
                // A causerie is an hour long: the careful model is four times slower, which on a
                // telephone is the difference between half an hour and an afternoon. Measured.
                val note = when {
                    m == RECOMMENDED -> " · " + stringResource(R.string.recommended)
                    m == Models.HIGH -> " · " + stringResource(R.string.quality_high_hint)
                    else -> ""
                }
                TextRow(
                    stringResource(if (m == Models.HIGH) R.string.quality_high else R.string.quality_normal),
                    inverted = quality == m.key, secondary = "${m.mb} MB$note$state", size = typo.title,
                ) { quality = m.key }
            }
            Rule(color = colors.fg)
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.action_cancel), onClick = onDismiss) }
                Box(Modifier.weight(1f)) {
                    // Inert until the language is settled: the only thing left to say.
                    val chosen = language
                    // "Start", not "write it down": half a row is not wide enough for the long
                    // label, and the sheet says what it is about already.
                    TextRow(
                        stringResource(R.string.action_start),
                        inverted = chosen != null,
                        onClick = if (chosen == null) null else {
                            { activity.transcribe(episode, chosen, quality); onDismiss() }
                        },
                    )
                }
            }
        }
    }
    if (picking) TextMenu(
        stringResource(R.string.language),
        (listOfNotNull(suggested) + Prompts.choices(Prefs.deviceLanguage())).distinct().map { code ->
            val ticked = code == (language ?: suggested)
            MenuItem(
                spokenLanguage(code),
                secondary = if (ticked && code == suggested && language == null) stringResource(R.string.language_last_time)
                            else if (ticked) "✓" else null,
            ) { language = code }
        },
        onDismiss = { picking = false },
    )
}

/** The language whisper is told to expect; "" means it works it out for itself. */
@Composable
fun spokenLanguage(code: String): String =
    if (code.isBlank()) stringResource(R.string.language_auto) else languageName(code)

/** A language as it is said in itself: "français", "English". */
fun languageName(code: String): String =
    java.util.Locale(code).getDisplayLanguage(java.util.Locale(code)).replaceFirstChar { it.lowercase() }

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
// Settings: what the app opens on, what it does by itself, the two files it exchanges, the look.
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
                // One line per setting, as in Reader's Tasks: rows all reading "on" above their
                // label are a column one deciphers rather than reads.
                Setting(R.string.opens_on, viewLabel(s.defaultView)) {
                    val i = Prefs.VIEWS.indexOf(s.defaultView).let { if (it < 0) 0 else it }
                    app.prefs.setDefaultView(Prefs.VIEWS[(i + 1) % Prefs.VIEWS.size])
                }
                Setting(R.string.wifi_only, onOff(s.wifiOnly)) { app.prefs.setWifiOnly(!s.wifiOnly) }
                Setting(R.string.auto_refresh, onOff(s.autoRefresh)) { app.prefs.setAutoRefresh(!s.autoRefresh) }
                Setting(
                    R.string.delete_when_played, onOff(s.deleteWhenPlayed),
                    secondary = R.string.delete_when_played_hint,
                ) { app.prefs.setDeleteWhenPlayed(!s.deleteWhenPlayed) }
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
                if (com.freedomfighter.readerspodcasts.net.Extractor.AVAILABLE) {
                    Rule(Modifier.padding(vertical = 8.dp))
                    // The app keeps yt-dlp current by itself, once a week; this row is for the
                    // morning when YouTube changes and one does not want to wait for the week.
                    // It shows the version in place, so "already up to date" can be checked.
                    val context = LocalContext.current
                    val version = remember(activity.busy) { com.freedomfighter.readerspodcasts.net.Extractor.version(context) }
                    TextRow(
                        stringResource(R.string.update_ytdlp),
                        secondary = activity.busy.ifBlank { version.ifBlank { stringResource(R.string.update_ytdlp_hint) } },
                        size = typo.title,
                    ) { activity.updateYtdlp() }
                }
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
private fun Setting(label: Int, value: String, secondary: Int? = null, onClick: () -> Unit) =
    TextRow(
        stringResource(R.string.setting_line, stringResource(label), value),
        secondary = secondary?.let { stringResource(it) },
        size = LocalTypo.current.title,
        onClick = onClick,
    )
