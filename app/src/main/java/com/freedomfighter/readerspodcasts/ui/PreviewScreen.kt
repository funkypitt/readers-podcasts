package com.freedomfighter.readerspodcasts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerspodcasts.App
import com.freedomfighter.readerspodcasts.Errors
import com.freedomfighter.readerspodcasts.MainActivity
import com.freedomfighter.readerspodcasts.R
import com.freedomfighter.readerspodcasts.net.Catalogue
import com.freedomfighter.readerspodcasts.net.Refresher

// ---------------------------------------------------------------------------------------------
// A podcast found by its name, before one follows it: who makes it, what it says of itself, its
// latest episodes with their dates — all read from its feed, nothing kept. Subscribing is the one
// word at the foot of the screen; a tap on the name in the list no longer subscribes by itself.
// ---------------------------------------------------------------------------------------------

/** How many of the latest episodes are shown: enough to see the rhythm, not the whole archive. */
private const val LATEST = 10

@Composable
fun PreviewScreen(nav: Nav, app: App, activity: MainActivity, found: Catalogue.Found) {
    val context = LocalContext.current
    val feeds by app.store.feeds.collectAsState()
    val followed = feeds.firstOrNull { Catalogue.key(it.url) == Catalogue.key(found.url) }
    var previewed by remember { mutableStateOf<Refresher.Previewed?>(null) }
    var failure by remember { mutableStateOf("") }
    BackHandler { nav.pop() }
    LaunchedEffect(found.url) {
        Refresher.preview(found.url)
            .onSuccess { previewed = it }
            .onFailure { e ->
                failure = if (e is Refresher.Refused) context.getString(e.reasonRes)
                else context.getString(R.string.feed_failed, (Errors.known(context, e) ?: e.message.orEmpty()).take(80))
            }
    }

    val parsed = previewed?.parsed
    val title = parsed?.title?.ifBlank { null } ?: found.title
    val author = (parsed?.author?.ifBlank { null } ?: found.author).ifBlank { Refresher.hostOf(found.url) }
    val count = parsed?.episodes?.size ?: found.episodes
    val latest = remember(parsed) { parsed?.episodes?.sortedByDescending { it.published }?.take(LATEST) ?: emptyList() }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.preview_title), onBack = { nav.pop() })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)) {
                item {
                    T(title, Modifier.padding(horizontal = rowPadH), align = TextAlign.Start)
                    Small(
                        listOf(author, if (count > 0) pluralStringResource(R.plurals.episodes_n, count, count) else "")
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        Modifier.padding(horizontal = rowPadH, vertical = 4.dp), maxLines = 2,
                    )
                }
                when {
                    failure.isNotBlank() -> item { Small(failure, Modifier.padding(horizontal = rowPadH, vertical = 16.dp), maxLines = 6) }
                    parsed == null -> item { Small(stringResource(R.string.adding_feed), Modifier.padding(horizontal = rowPadH, vertical = 16.dp)) }
                    else -> {
                        item {
                            if (parsed.description.isBlank()) Small(stringResource(R.string.preview_no_description), Modifier.padding(horizontal = rowPadH, vertical = 16.dp))
                            else LinkedText(parsed.description, Modifier.padding(horizontal = rowPadH, vertical = 12.dp))
                        }
                        item {
                            Rule(Modifier.padding(top = 8.dp))
                            Small(
                                stringResource(if (latest.isEmpty()) R.string.preview_no_episodes else R.string.preview_latest),
                                Modifier.padding(horizontal = rowPadH).padding(top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(latest, key = { it.id }) { e ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 8.dp)) {
                                T(e.title, maxLines = 2, align = TextAlign.Start)
                                Small(cut(listOf(relativeDate(e.published), if (e.durationMs > 0) com.freedomfighter.readerspodcasts.data.spoken(context, e.durationMs) else "")
                                    .filter { it.isNotBlank() }.joinToString(" · ")), maxLines = 1)
                            }
                        }
                    }
                }
            }
            Rule()
            // The one decision, kept in sight whatever is being read above it.
            when {
                activity.busy.isNotBlank() -> TextRow(activity.busy)
                followed != null -> TextRow(stringResource(R.string.preview_open), secondary = stringResource(R.string.already_followed)) {
                    app.prefs.setView(followed.id); nav.home()
                }
                else -> TextRow(stringResource(R.string.subscribe), inverted = true, secondary = cut(Refresher.hostOf(found.url))) {
                    val p = previewed
                    if (p != null) activity.subscribe(p) else activity.subscribe(found.url)
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}
