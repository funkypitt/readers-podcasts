package com.freedomfighter.readerspodcasts

import android.app.Application
import com.freedomfighter.readerspodcasts.data.Prefs
import com.freedomfighter.readerspodcasts.data.Store
import com.freedomfighter.readerspodcasts.net.Refresher
import com.freedomfighter.readerspodcasts.widget.LastWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    // lazy: the widget and the services can run before Application.onCreate is done
    val prefs: Prefs by lazy { Prefs(this) }
    val store: Store by lazy { Store(this).also { it.onChange = { LastWidgets.refresh(this) } } }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate(); prefs; store
    }

    /**
     * Refresh on opening, at most once an hour: a podcast feed publishes a few times a week, and
     * a phone opened twenty times a day should not fetch twenty times.
     */
    fun refreshIfStale() {
        if (!prefs.settings.value.autoRefresh) return
        val newest = store.feeds.value.maxOfOrNull { it.lastFetch } ?: return
        if (System.currentTimeMillis() - newest < 60 * 60 * 1000L) return
        refreshAll()
    }

    fun refreshAll() {
        scope.launch { Refresher.refreshAll(this@App, store) }
    }
}
