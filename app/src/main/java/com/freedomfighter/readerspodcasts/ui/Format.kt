package com.freedomfighter.readerspodcasts.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.freedomfighter.readerspodcasts.R
import com.freedomfighter.readerspodcasts.data.ThemeMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A date as one would say it out loud: today, yesterday, the day of the week within the week,
 * then the date itself. Never `18/09/2026` — this app writes words.
 */
fun relativeDate(context: Context, ms: Long): String {
    if (ms <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val days = daysBetween(then, now)
    val locale = Locale.getDefault()
    return when {
        days == 0 -> context.getString(R.string.today)
        days == 1 -> context.getString(R.string.yesterday)
        days in 2..6 -> SimpleDateFormat("EEEE", locale).format(then.time).replaceFirstChar { it.lowercase(locale) }
        then.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale).format(then.time)
        else -> SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMyyyy"), locale).format(then.time)
    }
}

@Composable
fun relativeDate(ms: Long): String = relativeDate(LocalContext.current, ms)

/** Whole days apart, by the calendar and not by 24-hour steps: at 00:30, midday was yesterday. */
private fun daysBetween(then: Calendar, now: Calendar): Int {
    val a = startOfDay(then)
    val b = startOfDay(now)
    return ((b - a) / (24L * 60 * 60 * 1000)).toInt()
}

private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** The name of a theme, as the settings row and the menu show it. */
fun themeName(context: Context, m: ThemeMode): String = context.getString(when (m) {
    ThemeMode.DARK -> R.string.theme_dark; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LAVANDE -> R.string.theme_lavande; ThemeMode.SAUGE -> R.string.theme_sauge; ThemeMode.NORDIQUE -> R.string.theme_nordique
    ThemeMode.TERRACOTTA -> R.string.theme_terracotta; ThemeMode.COQUETTE -> R.string.theme_coquette; ThemeMode.MILITANTE -> R.string.theme_militante
    ThemeMode.TORRIDE -> R.string.theme_torride; ThemeMode.NATURE -> R.string.theme_nature
})

@Composable
fun themeName(m: ThemeMode): String = themeName(LocalContext.current, m)
