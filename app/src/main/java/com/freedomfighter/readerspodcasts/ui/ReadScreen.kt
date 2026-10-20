package com.freedomfighter.readerspodcasts.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerspodcasts.App
import com.freedomfighter.readerspodcasts.MainActivity
import com.freedomfighter.readerspodcasts.R
import com.freedomfighter.readerspodcasts.data.Transcripts

/**
 * What was said, read as a text — paragraphs, with nothing of the sound about them.
 *
 * The other screen follows the sound line by line and is for listening; this one is for reading,
 * after or instead of listening, the way one reads an article. The lines are run together into
 * paragraphs on the pauses of the speech, the same paragraphs the exported `.txt` has, and the
 * text can be selected, because a passage worth keeping is copied from here and not from the
 * list of timed lines. No position is followed and no tap moves the sound: a reading that jumps
 * because the audio is still going in the background is not a reading.
 */
@Composable
fun ReadScreen(nav: Nav, app: App, activity: MainActivity, id: String) {
    val context = LocalContext.current
    val typo = LocalTypo.current
    val all by app.store.episodes.collectAsState()
    val episode = all.firstOrNull { it.id == id }
    var showTranslation by remember(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    BackHandler { nav.pop() }
    if (episode == null) { LaunchedEffect(Unit) { nav.pop() }; return }

    val source = remember(id, episode.transcript) { Transcripts.load(context, id)?.second.orEmpty() }
    val translation = remember(id, episode.translation) {
        episode.translation.takeIf { it.isNotBlank() }?.let { Transcripts.loadTranslation(context, id, it) }
    }
    // The paragraphs are the ones the .txt and the book in Reader's Books have — the shared rule,
    // another voice or a pause — so what is read here is what was sent out.
    val paragraphs: List<String> = remember(source, translation, showTranslation) {
        Transcripts.asText(if (showTranslation) translation.orEmpty() else source)
            .split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
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
            if (paragraphs.isEmpty()) {
                Small(stringResource(R.string.no_text_yet), Modifier.padding(horizontal = rowPadH, vertical = 16.dp), maxLines = 4)
            }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
                    items(paragraphs) { paragraph ->
                        T(
                            paragraph,
                            Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 8.dp),
                            size = typo.title,
                            align = TextAlign.Start,
                            lineHeightMul = 1.45f,
                        )
                    }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(episode.title, buildList {
            add(MenuItem(stringResource(R.string.read_text), secondary = stringResource(R.string.read_text_hint)) { nav.pop(); nav.push(Screen.Text(episode.id)) })
            add(MenuItem(stringResource(R.string.share_transcript)) { activity.shareTranscript(episode, showTranslation) })
            if (episode.transcriptUri.isNotBlank()) {
                add(MenuItem(stringResource(R.string.open_transcript), secondary = stringResource(R.string.transcript_saved)) { activity.openTranscript(episode) })
            }
        }, onDismiss = { menu = false })
    }
}
