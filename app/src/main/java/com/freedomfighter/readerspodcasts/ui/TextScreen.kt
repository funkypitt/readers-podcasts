package com.freedomfighter.readerspodcasts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerspodcasts.App
import com.freedomfighter.readerspodcasts.MainActivity
import com.freedomfighter.readerspodcasts.R
import com.freedomfighter.readerspodcasts.data.Line
import com.freedomfighter.readerspodcasts.data.clock
import com.freedomfighter.readerspodcasts.data.Transcripts

/**
 * What was said, read while it is being said.
 *
 * A screen with the sound's own controls, because reading along and having to leave in order to
 * pause is not reading along. The line being spoken is the inverted one, as everywhere else in
 * the app, and it scrolls itself into view; tapping any line sends the sound there. With a
 * translation in hand, one line at the top swaps the two — the translation is in blocks of some forty seconds rather than line by
 * line, because matching translated sentences to source sentences only holds three times in four
 * and a reading that drifts against the sound would be worse than no reading at all.
 */
@Composable
fun TextScreen(nav: Nav, app: App, activity: MainActivity, id: String) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val all by app.store.episodes.collectAsState()
    val episode = all.firstOrNull { it.id == id }
    var showTranslation by remember(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    BackHandler { nav.pop() }
    if (episode == null) { LaunchedEffect(Unit) { nav.pop() }; return }

    val source = remember(id, episode.transcript) { Transcripts.load(context, id) }
    val translation = remember(id, episode.translation) {
        episode.translation.takeIf { it.isNotBlank() }?.let { Transcripts.loadTranslation(context, id, it) }
    }
    val lines: List<Line> = (if (showTranslation) translation else source?.second).orEmpty()

    // Where the sound is: the episode's own position when it is the one playing, and the saved
    // one otherwise, so opening the text of something else still shows where one had left it.
    val playing = activity.ui.mediaId == id
    val position = if (playing) activity.ui.positionMs else episode.positionMs
    val current = remember(lines, position) { lines.indexOfLast { it.startMs <= position }.coerceAtLeast(0) }

    val listState = rememberLazyListState()
    LaunchedEffect(current, lines.size) {
        // Follow the sound, but never fight a finger: a list being dragged is left alone.
        if (lines.isNotEmpty() && !listState.isScrollInProgress) {
            runCatching { listState.animateScrollToItem(current.coerceAtMost(lines.lastIndex), -200) }
        }
    }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                episode.title,
                onBack = { nav.pop() },
                trailing = "⋯",
                onTrailing = { menu = true },
            )
            if (translation != null) {
                // Two words, the one being read inverted: the whole of the choice on offer.
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        TextRow(stringResource(R.string.transcript_word), inverted = !showTranslation, size = typo.title) { showTranslation = false }
                    }
                    Box(Modifier.weight(1f)) {
                        TextRow(stringResource(R.string.translation), inverted = showTranslation, size = typo.title) { showTranslation = true }
                    }
                }
                Rule()
            }
            // The sound, from here: one does not leave a reading to pause it.
            val playingThis = activity.ui.mediaId == id
            val sounding = playingThis && activity.ui.playing
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextRow(stringResource(R.string.back5), size = typo.title) { if (playingThis) activity.seekBy(-5_000) }
                }
                Box(Modifier.weight(1.2f)) {
                    TextRow(
                        (if (sounding) "❚❚" else "▶") + "  " + clock(position),
                        inverted = sounding, size = typo.title,
                    ) { if (playingThis) activity.toggle() else activity.play(episode) }
                }
                Box(Modifier.weight(1f)) {
                    TextRow(stringResource(R.string.fwd10), size = typo.title) { if (playingThis) activity.seekBy(10_000) }
                }
            }
            Rule()
            if (lines.isEmpty()) {
                Small(stringResource(R.string.no_text_yet), Modifier.padding(horizontal = rowPadH, vertical = 16.dp), maxLines = 4)
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                if (episode.description.isNotBlank()) {
                    item {
                        Small(
                            episode.description,
                            Modifier.padding(horizontal = rowPadH).padding(top = 4.dp, bottom = 12.dp),
                            maxLines = 6,
                        )
                    }
                }
                itemsIndexed(lines) { i, line ->
                    val here = i == current
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(if (here) colors.fg else Color.Transparent)
                            .noRippleClickable { activity.seekOrPlay(episode, line.startMs) }
                            .padding(horizontal = rowPadH, vertical = 8.dp)
                    ) {
                        T(
                            line.text,
                            size = typo.title,
                            color = if (here) colors.bg else colors.fg,
                            align = TextAlign.Start,
                            lineHeightMul = 1.45f,
                        )
                    }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(episode.title, buildList {
            add(MenuItem(stringResource(R.string.share_transcript)) { activity.shareTranscript(episode, showTranslation) })
            if (episode.transcriptUri.isNotBlank()) {
                add(MenuItem(stringResource(R.string.open_transcript), secondary = stringResource(R.string.transcript_saved)) { activity.openTranscript(episode) })
            }
            add(MenuItem(stringResource(R.string.transcribe_again)) { nav.pop(); activity.askTranscribe(episode) })
        }, onDismiss = { menu = false })
    }
}
