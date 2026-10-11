package com.freedomfighter.readerspodcasts.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * An exported file: written where the person chose to save it, with a copy kept for the share
 * menu (Telegram, kDrive, Drive, e-mail…) offered right after. The copy is cleaned up at the
 * next export and at every start.
 */
object Exports {
    /** The two files that carry everything, named after the app that writes them. */
    const val OPML_NAME = "readers-podcasts-subscriptions.opml"
    const val SETTINGS_NAME = "readers-podcasts-settings.json"
    const val OPML_MIME = "text/xml"
    const val SETTINGS_MIME = "application/json"

    private fun dir(context: Context) = File(context.cacheDir, "exports")

    fun cleanUp(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }

    /** Writes the text to the chosen document and keeps the copy to share. False when the document could not be written. */
    fun save(context: Context, uri: Uri, name: String, text: String): Boolean {
        cleanUp(context)
        val written = runCatching {
            // "wt", not the default: written over a longer file, plain "w" leaves the end of the
            // old one after the new — a file no other program can read.
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        if (written) File(dir(context).apply { mkdirs() }, name).writeText(text)
        return written
    }

    /** Hands the copy of the last saved export to the share menu. */
    fun share(context: Context, name: String, mime: String, chooserTitle: String) {
        val file = File(dir(context), name)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".exports", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        runCatching { context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }
}
