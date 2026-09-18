package com.freedomfighter.readerspodcasts.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class FontChoice { SERIF, SANS, MONO }
enum class TextSize { SMALL, MEDIUM, LARGE }
enum class Align { LEFT, CENTER }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val font: FontChoice = FontChoice.SANS,
    val textSize: TextSize = TextSize.MEDIUM,
    val align: Align = Align.LEFT,
    val haptics: Boolean = true,
    val speed: Float = 1f,
    /** Download over wifi only — a podcast is tens of megabytes. */
    val wifiOnly: Boolean = true,
    /** Refresh the subscriptions when the app is opened, at most once an hour. */
    val autoRefresh: Boolean = true,
    /** Delete the file once the episode has been heard through. */
    val deleteWhenPlayed: Boolean = true,
    /** Which list the home screen shows: one of the three views, or a feed's id. */
    val view: String = Prefs.VIEW_CHANNELS,
    /** The one it opens on. */
    val defaultView: String = Prefs.VIEW_CHANNELS,
)

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        theme = enumOr(sp.getString("theme", null), ThemeMode.DARK),
        font = enumOr(sp.getString("font", null), FontChoice.SANS),
        textSize = enumOr(sp.getString("text_size", null), TextSize.MEDIUM),
        align = enumOr(sp.getString("align", null), Align.LEFT),
        haptics = sp.getBoolean("haptics", true),
        speed = sp.getFloat("speed", 1f),
        wifiOnly = sp.getBoolean("wifi_only", true),
        autoRefresh = sp.getBoolean("auto_refresh", true),
        deleteWhenPlayed = sp.getBoolean("delete_when_played", true),
        defaultView = known(sp.getString("default_view", VIEW_CHANNELS)),
        // The stored view goes through the same mapping: a phone updated from 0.1 held "queue"
        // there, and a name nothing answers to left the screen empty under a title that lied.
        view = stored(sp.getString("view", null)) ?: known(sp.getString("default_view", VIEW_CHANNELS)),
    )

    /**
     * A view read from the settings, made sense of. The 0.1 names are mapped rather than
     * dropped: a phone updated from it must not open on a list that no longer exists.
     */
    /** A stored view: one of the three, a feed's id, or an old name to be mapped. */
    private fun stored(name: String?): String? = when (name) {
        null, "" -> null
        "queue" -> VIEW_CHANNELS
        "new" -> VIEW_EPISODES
        else -> name
    }

    private fun known(name: String?): String = when (name) {
        VIEW_CHANNELS, VIEW_EPISODES, VIEW_FAVOURITES -> name
        "new" -> VIEW_EPISODES
        else -> VIEW_CHANNELS
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    fun setTheme(m: ThemeMode) = sp.edit().putString("theme", m.name).apply()
    fun setFont(f: FontChoice) = sp.edit().putString("font", f.name).apply()
    fun setTextSize(t: TextSize) = sp.edit().putString("text_size", t.name).apply()
    fun setAlign(a: Align) = sp.edit().putString("align", a.name).apply()
    fun setHaptics(v: Boolean) = sp.edit().putBoolean("haptics", v).apply()
    fun setSpeed(v: Float) = sp.edit().putFloat("speed", v).apply()
    fun setWifiOnly(v: Boolean) = sp.edit().putBoolean("wifi_only", v).apply()
    fun setAutoRefresh(v: Boolean) = sp.edit().putBoolean("auto_refresh", v).apply()
    fun setDeleteWhenPlayed(v: Boolean) = sp.edit().putBoolean("delete_when_played", v).apply()
    fun setView(v: String) = sp.edit().putString("view", v).apply()
    fun setDefaultView(v: String) = sp.edit().putString("default_view", v).putString("view", v).apply()

    fun toggleTheme(systemIsDark: Boolean) {
        val dark = when (_settings.value.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemIsDark }
        setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK)
    }

    /** Everything settable, for the backup file. Keys match the JSON the desktop reads and writes. */
    fun exportMap(): Map<String, Any> = _settings.value.let { s ->
        mapOf(
            "theme" to s.theme.name, "font" to s.font.name, "text_size" to s.textSize.name,
            "align" to s.align.name, "haptics" to s.haptics, "speed" to s.speed,
            "wifi_only" to s.wifiOnly, "auto_refresh" to s.autoRefresh,
            "delete_when_played" to s.deleteWhenPlayed, "default_view" to s.defaultView,
        )
    }

    /** The view is left out on purpose: which list one was reading is not worth carrying over. */
    fun importMap(m: Map<String, Any?>) {
        val e = sp.edit()
        (m["theme"] as? String)?.let { e.putString("theme", it) }
        (m["font"] as? String)?.let { e.putString("font", it) }
        (m["text_size"] as? String)?.let { e.putString("text_size", it) }
        (m["align"] as? String)?.let { e.putString("align", it) }
        (m["haptics"] as? Boolean)?.let { e.putBoolean("haptics", it) }
        (m["speed"] as? Number)?.let { e.putFloat("speed", it.toFloat()) }
        (m["wifi_only"] as? Boolean)?.let { e.putBoolean("wifi_only", it) }
        (m["auto_refresh"] as? Boolean)?.let { e.putBoolean("auto_refresh", it) }
        (m["delete_when_played"] as? Boolean)?.let { e.putBoolean("delete_when_played", it) }
        (m["default_view"] as? String)?.let { e.putString("default_view", it) }
        e.apply()
    }

    companion object {
        const val VIEW_CHANNELS = "channels"
        const val VIEW_EPISODES = "episodes"
        const val VIEW_FAVOURITES = "favourites"
        val VIEWS = listOf(VIEW_CHANNELS, VIEW_EPISODES, VIEW_FAVOURITES)

        /**
         * The list a stored view really names. [isFeed] says whether it is the id of a feed one
         * is still subscribed to; anything else — an old name, a feed since removed — falls back
         * to the channels, which is the one list that is never empty when there are any.
         */
        fun viewOrChannels(stored: String?, isFeed: Boolean): String = when {
            stored.isNullOrBlank() -> VIEW_CHANNELS
            isFeed -> stored
            stored == "queue" -> VIEW_CHANNELS
            stored == "new" -> VIEW_EPISODES
            stored in VIEWS -> stored
            else -> VIEW_CHANNELS
        }
        val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }
}
