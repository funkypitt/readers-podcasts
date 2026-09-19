package com.freedomfighter.readerspodcasts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerspodcasts.R
import androidx.compose.ui.res.stringResource

// ---------------------------------------------------------------------------------------------
// Text primitives. Everything on screen goes through these so the look stays uniform.
// ---------------------------------------------------------------------------------------------

@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = LocalTypo.current.tile,
    color: Color = LocalColors.current.fg,
    align: TextAlign = LocalTypo.current.textAlign,
    maxLines: Int = Int.MAX_VALUE,
    lineHeightMul: Float = 1.25f,
    softWrap: Boolean = true
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontFamily = LocalTypo.current.family,
            fontWeight = LocalTypo.current.weight,
            fontSize = size,
            lineHeight = size * lineHeightMul,
            textAlign = align
        ),
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = if (softWrap) TextOverflow.Ellipsis else TextOverflow.Clip
    )
}

@Composable
fun Small(text: String, modifier: Modifier = Modifier, color: Color = LocalColors.current.dim, maxLines: Int = 2, align: TextAlign = LocalTypo.current.textAlign) =
    T(text, modifier, size = LocalTypo.current.small, color = color, maxLines = maxLines, align = align)

/** Hairline rule in the foreground colour. */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = LocalColors.current.rule) {
    val c = color
    Canvas(modifier.fillMaxWidth().height(1.dp)) { drawRect(c) }
}

/** Padding used by every text row. */
val rowPadH = 28.dp
val rowPadV = 20.dp

fun Modifier.noRippleClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        enabled = enabled,
        onClick = onClick
    )
)

/** A tappable line of text — the universal control of this launcher. */
@Composable
fun TextRow(
    text: String,
    modifier: Modifier = Modifier,
    inverted: Boolean = false,
    secondary: String? = null,
    size: TextUnit = LocalTypo.current.tile,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalColors.current
    val bg = if (inverted) colors.fg else Color.Transparent
    val fg = if (inverted) colors.bg else colors.fg
    val dim = if (inverted) colors.bg.copy(alpha = 0.6f) else colors.dim
    Column(
        modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (onClick != null) Modifier.noRippleClickable(onClick = onClick) else Modifier)
            .padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)
    ) {
        T(text, size = size, color = fg, maxLines = 1)
        if (secondary != null) Small(secondary, color = dim, maxLines = 1)
    }
}

/**
 * Title line at the top of a screen. Tapping it goes back, or opens whatever [onTitle] says.
 * [actions] are the signs to its right, in order, before [trailing]: refresh and add, then ⋯.
 */
/** A sign in the title bar is hit with a thumb, not a stylus: a square, and room around it. */
private val glyphTarget = 44.dp
private val glyphGap = 12.dp

@Composable
fun ScreenTitle(
    title: String,
    onBack: (() -> Unit)?,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
    onTitle: (() -> Unit)? = null,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val colors = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            // A bar of one height everywhere, tall enough to hold a square a thumb can hit. The
            // padding used to set the height; now it only keeps the title off the squares.
            .heightIn(min = 56.dp)
            .padding(horizontal = rowPadH, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).then(
                if (onBack != null) Modifier.noRippleClickable(onClick = onBack)
                else if (onTitle != null) Modifier.noRippleClickable(onClick = onTitle) else Modifier
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                T("←", size = LocalTypo.current.title, color = colors.dim, align = TextAlign.Start)
                Spacer(Modifier.height(0.dp).padding(horizontal = 8.dp))
            }
            T(title, size = LocalTypo.current.title, color = colors.dim, maxLines = 1, align = TextAlign.Start)
        }
        // Each sign gets a square of its own to be hit in, and a gap of untouchable space
        // between them: side by side with nothing in between, ↻ and + shared a border, and a
        // thumb aiming at one had every chance of getting the other.
        actions.forEachIndexed { i, (label, action) ->
            if (i > 0) Spacer(Modifier.width(glyphGap))
            Box(
                Modifier.size(glyphTarget).noRippleClickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) { T(label, size = LocalTypo.current.title, color = colors.dim, align = TextAlign.Center) }
        }
        if (trailing != null) {
            if (actions.isNotEmpty()) Spacer(Modifier.width(glyphGap))
            Box(
                Modifier.size(glyphTarget)
                    .then(if (onTrailing != null) Modifier.noRippleClickable(onClick = onTrailing) else Modifier),
                // Flush with the margin, as it was: the square grows towards the inside.
                contentAlignment = Alignment.CenterEnd,
            ) { T(trailing, size = LocalTypo.current.title, align = TextAlign.End) }
        }
    }
    Rule()
}


/** Web addresses in a text, as they are written: `https://…`, `www.…`, and bare `example.com/x`. */
val LINK = Regex("""(https?://[^\s<>"')\]]+|www\.[^\s<>"')\]]+)""", RegexOption.IGNORE_CASE)

/**
 * A text whose addresses can be followed and kept.
 *
 * A description is where a podcast puts what it is talking about — a book, a page, a subscription
 * form — and a wall of plain text makes those unreachable: one had to retype them by hand. A tap
 * opens the address; holding it copies it, and holding anywhere else copies the whole text, which
 * is what one wants when the interesting part is a name rather than a link.
 */
@Composable
fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = LocalTypo.current.tile,
    color: Color = LocalColors.current.fg,
    maxLines: Int = Int.MAX_VALUE,
    onCopied: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val clipboard = LocalClipboardManager.current
    val spans = remember(text) { LINK.findAll(text).map { it.range to it.value }.toList() }
    val annotated = remember(text, spans) {
        buildAnnotatedString {
            append(text)
            spans.forEach { (range, _) ->
                addStyle(
                    SpanStyle(color = colors.fg, textDecoration = TextDecoration.Underline),
                    range.first, range.last + 1,
                )
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    fun linkAt(offset: Offset): String? {
        val l = layout ?: return null
        val i = l.getOffsetForPosition(offset)
        return spans.firstOrNull { (range, _) -> i >= range.first && i <= range.last + 1 }?.second
    }
    BasicText(
        text = annotated,
        modifier = modifier.pointerInput(spans) {
            detectTapGestures(
                onTap = { where ->
                    val url = linkAt(where) ?: return@detectTapGestures
                    val full = if (url.startsWith("http", true)) url else "https://$url"
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                onLongPress = { where ->
                    val what = linkAt(where) ?: text
                    clipboard.setText(AnnotatedString(what))
                    onCopied(what)
                },
            )
        },
        style = TextStyle(
            color = color,
            fontFamily = typo.family,
            fontWeight = typo.weight,
            fontSize = size,
            lineHeight = size * 1.4f,
            textAlign = TextAlign.Start,
        ),
        maxLines = maxLines,
        onTextLayout = { layout = it },
        overflow = TextOverflow.Ellipsis,
    )
}

/** Full-screen page frame with the theme background. */
@Composable
fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(LocalColors.current.bg)) { content() }
}

// ---------------------------------------------------------------------------------------------
// Menus and prompts: text-only bottom sheets.
// ---------------------------------------------------------------------------------------------

data class MenuItem(val label: String, val secondary: String? = null, val action: () -> Unit)

/**
 * A menu is a sheet of text lines anchored at the bottom, above a scrim.
 * Tapping outside or pressing back dismisses it.
 */
@Composable
fun TextMenu(title: String?, items: List<MenuItem>, onDismiss: () -> Unit, footer: List<MenuItem> = emptyList()) {
    val colors = LocalColors.current
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = 0.6f))
            .noRippleClickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colors.bg)
                .noRippleClickable { }
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Rule(color = colors.fg)
            if (title != null) {
                Small(title, Modifier.padding(horizontal = rowPadH).padding(top = 14.dp, bottom = 2.dp), maxLines = 1)
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEach { item ->
                    TextRow(item.label, secondary = item.secondary, onClick = {
                        onDismiss()
                        item.action()
                    })
                }
                if (footer.isNotEmpty()) {
                    Rule(Modifier.padding(vertical = 6.dp))
                    footer.forEach { item ->
                        TextRow(item.label, secondary = item.secondary, size = LocalTypo.current.title, onClick = {
                            onDismiss()
                            item.action()
                        })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Single-line text prompt (category name, city, task…). */
@Composable
fun TextPrompt(
    title: String,
    initial: String = "",
    confirm: String = stringResource(R.string.action_ok),
    password: Boolean = false,
    keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    /** A suggestion opens selected, so the first key replaces it rather than landing after it. */
    selectAll: Boolean = false,
    onDone: (String) -> Unit,
    onCancel: () -> Unit
) {
    val colors = LocalColors.current
    var field by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                initial,
                selection = if (selectAll) androidx.compose.ui.text.TextRange(0, initial.length)
                else androidx.compose.ui.text.TextRange(initial.length),
            )
        )
    }
    val value = field.text
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Centred in whatever the keyboard leaves free, never under it.
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = 0.6f))
            .noRippleClickable(onClick = onCancel)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .background(colors.bg)
                .noRippleClickable { }
        ) {
            Rule(color = colors.fg)
            Small(title, Modifier.padding(horizontal = rowPadH).padding(top = 14.dp))
            ReaderTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 10.dp).focusRequester(focus),
                imeAction = ImeAction.Done,
                onImeAction = { if (value.isNotBlank()) onDone(value.trim()) },
                password = password,
                keyboard = keyboard
            )
            Rule()
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.action_cancel), onClick = onCancel) }
                Box(Modifier.weight(1f)) {
                    TextRow(confirm, inverted = value.isNotBlank(), onClick = { if (value.isNotBlank()) onDone(value.trim()) })
                }
            }
            Rule(color = colors.fg)
        }
    }
}

@Composable
fun ReaderTextField(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    imeAction: ImeAction = ImeAction.Search,
    onImeAction: () -> Unit = {},
    password: Boolean = false,
    keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text
) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(color = colors.fg, fontFamily = typo.family, fontWeight = typo.weight, fontSize = typo.tile),
        cursorBrush = SolidColor(colors.fg),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else keyboard),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) T(placeholder, color = colors.dim, align = TextAlign.Start)
                inner()
            }
        }
    )
}

@Composable
fun ReaderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    imeAction: ImeAction = ImeAction.Search,
    onImeAction: () -> Unit = {},
    password: Boolean = false,
    keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text
) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(color = colors.fg, fontFamily = typo.family, fontWeight = typo.weight, fontSize = typo.tile),
        cursorBrush = SolidColor(colors.fg),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else keyboard),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) T(placeholder, color = colors.dim, align = TextAlign.Start)
                inner()
            }
        }
    )
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** Tap and long press on the same row, without ripple. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.pressable(onClick: () -> Unit, onLongPress: () -> Unit): Modifier = this.then(
    Modifier.combinedClickable(interactionSource = MutableInteractionSource(), indication = null, onLongClick = onLongPress, onClick = onClick)
)

/** One haptic tick, if enabled. */
@Composable
fun rememberTick(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val enabled = LocalHaptics.current
    return remember(enabled) { { if (enabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
}
