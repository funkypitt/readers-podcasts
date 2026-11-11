package com.freedomfighter.readerspodcasts

import com.freedomfighter.readerspodcasts.data.Episode
import com.freedomfighter.readerspodcasts.data.feedLanguage
import com.freedomfighter.readerspodcasts.data.languageChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The list of spoken languages in the sheet that starts a transcription. */
class LanguageChoicesTest {
    @Test fun listeningComesFirstThenTheChannelsLastLanguage() =
        assertEquals(listOf("", "en", "fr", "de", "es", "pt", "ru", "it"), languageChoices("en", "fr"))

    @Test fun withoutALastLanguageThePhonesComesSecond() =
        assertEquals(listOf("", "fr", "en", "de", "es", "pt", "ru", "it"), languageChoices(null, "fr"))

    @Test fun eachLanguageOnce() {
        val list = languageChoices("it", "it")
        assertEquals(list.distinct(), list)
        assertEquals("it", list[1])
    }

    @Test fun theLastLanguageIsReadFromTheChannelsTranscripts() {
        fun ep(id: String, published: Long, lang: String) =
            Episode(id = id, feedId = "f", title = id, published = published, mediaUrl = "m", transcript = lang.isNotBlank(), transcriptLanguage = lang)
        val episodes = listOf(ep("old", 1, "fr"), ep("new", 2, "en"), ep("none", 3, ""))
        assertEquals("en", feedLanguage(episodes, "f"))
        assertNull(feedLanguage(episodes, "other"))
    }
}
