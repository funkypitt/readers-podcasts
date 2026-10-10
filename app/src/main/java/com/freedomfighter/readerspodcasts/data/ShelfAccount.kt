package com.freedomfighter.readerspodcasts.data

import org.json.JSONObject

/** The library of Reader's Books as a Reader's credentials file names it. */
data class ShelfAccount(val url: String, val username: String, val password: String) {
    companion object {
        /**
         * Null when the text is not a Reader's credentials file; an account with a blank address
         * when it is one but holds nothing a library can be made of. Reader's Books' own section
         * first, then Magazine Reader's (the same kind of drive), then the drive and login of
         * Scanner, Notes or Recorder (never their folder).
         */
        fun read(text: String): ShelfAccount? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (root.optString("format") != "readers-credentials") return null
            fun JSONObject.str(key: String) = if (has(key) && !isNull(key)) (opt(key) as? String).orEmpty() else ""
            for (name in listOf("readers-books", "magazine-reader")) {
                root.optJSONObject(name)?.let { s -> if (s.str("url").isNotBlank()) return ShelfAccount(s.str("url"), s.str("username"), s.str("password")) }
            }
            for (name in listOf("readers-scanner", "readers-notes", "readers-recorder")) {
                root.optJSONObject(name)?.let { s -> if (s.str("server").isNotBlank()) return ShelfAccount(s.str("server"), s.str("username"), s.str("password")) }
            }
            return ShelfAccount("", "", "")
        }
    }
}
