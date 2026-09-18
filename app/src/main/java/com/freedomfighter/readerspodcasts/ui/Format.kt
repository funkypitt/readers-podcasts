package com.freedomfighter.readerspodcasts.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.freedomfighter.readerspodcasts.R
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
        then.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat("d MMM", locale).format(then.time)
        else -> SimpleDateFormat("d MMM yyyy", locale).format(then.time)
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
