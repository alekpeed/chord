package com.alekpeed.hearsay.core.data.mapper

import com.alekpeed.hearsay.core.model.music.Chord
import kotlinx.serialization.json.Json

/**
 * The structured chord as it is stored.
 *
 * Unknown keys are ignored on the way in: a project written by a later version of the app that
 * added a field must still open here rather than losing the whole chord.
 */
internal object ChartJson {
    val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(chord: Chord?): String? = chord?.let { format.encodeToString(it) }

    fun decode(json: String?): Chord? = json?.let {
        runCatching { format.decodeFromString<Chord>(it) }.getOrNull()
    }
}
