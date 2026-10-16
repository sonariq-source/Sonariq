package com.music.sonariq.playback.vibe

import com.music.sonariq.data.scrobbling.LastFM
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * LastFmTags: learns a track's vibe from its Last.fm tags.
 *
 * Calls the public track.getTopTags endpoint through the app's existing
 * Last.fm configuration (read-only; the auth/client in LastFM.kt is not
 * touched), then converts community tags into a [VibeVector].
 * Never throws — any failure returns an empty tag list.
 */
object LastFmTags {

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            expectSuccess = false
        }
    }

    @Serializable
    private data class TagEntry(val name: String = "")

    @Serializable
    private data class TopTags(
        @SerialName("tag") val tag: List<TagEntry> = emptyList(),
    )

    @Serializable
    private data class TopTagsResponse(
        @SerialName("toptags") val toptags: TopTags? = null,
    )

    /**
     * Fetches the top tags for [artist] / [track] from Last.fm.
     * Returns tag names lowercased, max 10, or emptyList() on any failure.
     */
    suspend fun getTopTags(artist: String, track: String): List<String> {
        return runCatching {
            val apiKey = LastFM.currentConfig().apiKey
            val response: HttpResponse =
                client.get(LastFM.DEFAULT_API_ENDPOINT) {
                    parameter("method", "track.getTopTags")
                    parameter("artist", artist)
                    parameter("track", track)
                    if (apiKey.isNotBlank()) parameter("api_key", apiKey)
                    parameter("format", "json")
                    header(HttpHeaders.UserAgent, "Sonariq (https://github.com/sonariq-source/Sonariq)")
                }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) return@runCatching emptyList<String>()
            if (text.contains("\"error\"")) return@runCatching emptyList<String>()
            val parsed = json.decodeFromString<TopTagsResponse>(text)
            parsed.toptags?.tag
                ?.mapNotNull { it.name.lowercase().takeIf { name -> name.isNotBlank() } }
                ?.take(10)
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Converts Last.fm tags into a [VibeVector].
     *
     * Matching is case-insensitive on substrings. Each tag that matches at
     * least one keyword of a vibe adds 1.0 to that vibe (a tag can feed
     * multiple vibes). The result is normalized so the max dimension is 1.0.
     */
    fun tagsToVibe(tags: List<String>): VibeVector {
        val sums = FloatArray(10)
        tags.forEach { rawTag ->
            val tag = rawTag.lowercase()
            for (i in TAG_KEYWORDS.indices) {
                if (TAG_KEYWORDS[i].any { keyword -> tag.contains(keyword) }) {
                    sums[i] += 1.0f
                }
            }
        }
        val max = sums.maxOrNull() ?: 0f
        if (max <= 0f) return VibeVector()
        val n = { i: Int -> sums[i] / max }
        return VibeVector(
            sad = n(0),
            happy = n(1),
            chill = n(2),
            energetic = n(3),
            angry = n(4),
            romantic = n(5),
            nostalgic = n(6),
            party = n(7),
            focus = n(8),
            workout = n(9),
        )
    }

    /**
     * Keyword lists per vibe dimension, in VibeVector field order
     * (sad, happy, chill, energetic, angry, romantic, nostalgic, party,
     * focus, workout). Matched against tags as substrings.
     */
    private val TAG_KEYWORDS: List<List<String>> = listOf(
        // sad
        listOf("sad", "melancholy", "melancholic", "heartbreak", "depressing", "emo", "ballad"),
        // happy
        listOf("happy", "feel good", "feel-good", "uplifting", "cheerful", "summer"),
        // chill
        listOf("chill", "chillout", "relaxing", "mellow", "downtempo", "lofi", "lo-fi", "ambient"),
        // energetic
        listOf("energetic", "upbeat", "dance", "edm", "electronic"),
        // angry
        listOf("angry", "aggressive", "metal", "hardcore", "punk"),
        // romantic
        listOf("romantic", "love", "sexy", "slow jam"),
        // nostalgic
        listOf("nostalgic", "oldies", "80s", "90s", "classic", "retro"),
        // party
        listOf("party", "club", "dance", "pop"),
        // focus
        listOf("focus", "study", "concentration", "instrumental", "classical", "jazz"),
        // workout
        listOf("workout", "gym", "running", "motivation", "pump"),
    )
}
