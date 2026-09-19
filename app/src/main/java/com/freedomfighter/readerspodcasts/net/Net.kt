package com.freedomfighter.readerspodcasts.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** The little the app needs of the network: one connection, honestly made. */
object Net {
    const val AGENT = "Readers-Podcasts/1.0 (+https://gallaz.ch/eink)"

    /**
     * HttpURLConnection follows redirects, but never from http to https — which is exactly the
     * hop most feeds make — so redirects are followed here instead, a few at most.
     */
    fun open(url: String, range: Long = 0, hops: Int = 0): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", AGENT)
        c.setRequestProperty("Accept-Encoding", "gzip")
        // Without it, a reader in Europe asking for a channel page is sent to YouTube's consent
        // page, which names no channel: a `@handle` then resolves to nothing. Measured from here.
        if (URL(url).host.endsWith("youtube.com")) c.setRequestProperty("Cookie", "SOCS=CAI")
        if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
        val code = c.responseCode
        if (code in 301..308 && hops < 5) {
            val next = c.getHeaderField("Location")
            if (!next.isNullOrBlank()) {
                val absolute = URL(URL(url), next).toString()
                c.disconnect()
                return open(absolute, range, hops + 1)
            }
        }
        return c
    }

    fun body(c: HttpURLConnection): InputStream =
        if (c.contentEncoding?.contains("gzip", true) == true) GZIPInputStream(c.inputStream) else c.inputStream

    /** Whether the connection is one to download tens of megabytes over. */
    fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
