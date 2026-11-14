package com.freedomfighter.readerspodcasts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerspodcasts.App
import com.freedomfighter.readerspodcasts.MainActivity
import com.freedomfighter.readerspodcasts.R

// ---------------------------------------------------------------------------------------------
// One channel's own settings: whether its new episodes come down by themselves, and how much of
// the start and the end of each episode to leave out — the same jingle, the same advertisement
// read before every episode, the same trailer for the next one after it.
// ---------------------------------------------------------------------------------------------

/** The lengths offered at once, in seconds; any other is typed. */
private val SKIP_CHOICES = listOf(0, 10, 15, 20, 30, 45, 60, 90, 120)

@Composable
fun ChannelScreen(nav: Nav, app: App, activity: MainActivity, id: String) {
    val feeds by app.store.feeds.collectAsState()
    val feed = feeds.firstOrNull { it.id == id }
    val typo = LocalTypo.current
    // Which of the two lengths is being chosen ("start" / "end"), and whether it is being typed.
    var choosing by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf<String?>(null) }
    BackHandler { nav.pop() }
    if (feed == null) { LaunchedEffect(Unit) { nav.pop() }; return }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(feed.title, onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                TextRow(
                    stringResource(R.string.setting_line, stringResource(R.string.auto_download),
                        stringResource(if (feed.autoDownload) R.string.on else R.string.off)),
                    size = typo.title,
                ) { app.store.updateFeed(id) { it.copy(autoDownload = !it.autoDownload) } }
                TextRow(
                    stringResource(R.string.setting_line, stringResource(R.string.skip_start), skipLabel(feed.skipStartSec)),
                    secondary = stringResource(R.string.skip_start_hint),
                    size = typo.title,
                ) { choosing = "start" }
                TextRow(
                    stringResource(R.string.setting_line, stringResource(R.string.skip_end), skipLabel(feed.skipEndSec)),
                    secondary = stringResource(R.string.skip_end_hint),
                    size = typo.title,
                ) { choosing = "end" }
                Rule(Modifier.padding(vertical = 8.dp))
                Small(feed.url, Modifier.padding(horizontal = rowPadH), maxLines = 3)
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }

        fun set(which: String, seconds: Int) = app.store.updateFeed(id) {
            val s = seconds.coerceIn(0, MAX_SKIP_SEC)
            if (which == "start") it.copy(skipStartSec = s) else it.copy(skipEndSec = s)
        }

        choosing?.let { which ->
            val now = if (which == "start") feed.skipStartSec else feed.skipEndSec
            TextMenu(
                stringResource(if (which == "start") R.string.skip_start else R.string.skip_end),
                SKIP_CHOICES.map { sec ->
                    MenuItem(skipLabel(sec) + if (sec == now) "  ✓" else "") { set(which, sec) }
                } + MenuItem(
                    stringResource(R.string.skip_other) + if (now !in SKIP_CHOICES) "  ✓" else "",
                    secondary = if (now !in SKIP_CHOICES) skipLabel(now) else null,
                ) { typing = which },
                onDismiss = { choosing = null },
            )
        }

        typing?.let { which ->
            val now = if (which == "start") feed.skipStartSec else feed.skipEndSec
            TextPrompt(
                stringResource(R.string.skip_seconds),
                initial = if (now > 0) now.toString() else "",
                keyboard = KeyboardType.Number,
                selectAll = true,
                onDone = { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { set(which, it) }; typing = null },
                onCancel = { typing = null },
            )
        }
    }
}

/** An hour: more than that is a slip of the finger, not a jingle. */
private const val MAX_SKIP_SEC = 3600

@Composable
private fun skipLabel(seconds: Int): String =
    if (seconds <= 0) stringResource(R.string.off) else stringResource(R.string.duration_s, seconds)
