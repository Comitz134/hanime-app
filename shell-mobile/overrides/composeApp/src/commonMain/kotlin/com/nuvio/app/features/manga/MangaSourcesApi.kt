package com.nuvio.app.features.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where the manga tab's catalogues come from, as the app sees it.
 *
 * The server owns the repositories and runs their scripts; this is the screen's
 * side of that: one answer with every repository and every source, and four
 * writes - add, remove, refresh, on/off. The state is snapshot state rather than
 * a flow because there is exactly one screen that reads it and it is a settings
 * section, not a background service: a write redraws the rows, which is all it
 * has to do.
 */

private val sourcesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
internal data class MangaSourceRepo(
    val url: String = "",
    val name: String = "",
    val version: String = "",
    /** "sekai" for a JSON index of scripts, "mihon" for an extension repository. */
    val format: String = "sekai",
    val builtin: Boolean = false,
    val updated: Long = 0L,
    val error: String = "",
    /** Every source the repository offers. */
    val count: Int = 0,
    /** The sources the reader turned on. */
    val installed: Int = 0,
    val extensions: Int = 0,
) {
    val isMihon: Boolean get() = format == "mihon"
}

/** One catalogue a repository installed. [native] is the app's own Mangafire. */
@Serializable
internal data class MangaSourceInfo(
    val id: String = "",
    val name: String = "",
    val lang: String = "",
    val version: String = "",
    val description: String = "",
    val icon: String = "",
    val repo: String = "",
    val enabled: Boolean = true,
    val native: Boolean = false,
)

@Serializable
private data class MangaSourcesResponse(
    val repos: List<MangaSourceRepo> = emptyList(),
    val sources: List<MangaSourceInfo> = emptyList(),
)

/** One catalogue inside a Mihon extension package. */
@Serializable
internal data class MangaRepoSource(
    val id: String = "",
    val name: String = "",
    val lang: String = "",
    val enabled: Boolean = false,
)

/** One extension package a Mihon repository offers, and its catalogues. */
@Serializable
internal data class MangaRepoExtension(
    val name: String = "",
    val pkg: String = "",
    val version: String = "",
    val apk: String = "",
    val icon: String = "",
    val contentWarning: Int = 0,
    /** True when at least one of its sources is on. */
    val enabled: Boolean = false,
    val sources: List<MangaRepoSource> = emptyList(),
)

/**
 * One repository, opened.
 *
 * A SEKAI index answers with `sources`; a Mihon index answers with
 * `extensions`, each holding its own `sources`. The screen draws whichever of
 * the two the repository has, so it never has to know which kind it is.
 */
@Serializable
internal data class MangaRepoDetail(
    val url: String = "",
    val name: String = "",
    val format: String = "sekai",
    val error: String = "",
    val enabled: Int = 0,
    val total: Int = 0,
    val sources: List<MangaSourceInfo> = emptyList(),
    val extensions: List<MangaRepoExtension> = emptyList(),
)

/** A repository's answer, or the sentence to show instead of it. */
internal data class RepoOpen(
    val detail: MangaRepoDetail? = null,
    val error: String? = null,
)

internal data class MangaSourcesState(
    val loading: Boolean = false,
    val repos: List<MangaSourceRepo> = emptyList(),
    val sources: List<MangaSourceInfo> = emptyList(),
    /** A repository URL that is being added, refreshed or removed, or "add". */
    val busy: String? = null,
    val error: String? = null,
) {
    fun sourcesOf(repo: MangaSourceRepo): List<MangaSourceInfo> =
        sources.filter { it.repo == repo.url }

    val enabledCount: Int get() = sources.count { it.enabled }
}

internal object MangaSourcesApi {

    var state by mutableStateOf(MangaSourcesState())
        private set

    private val http = createApiHttpClient()
    private var loading = false

    /** The first load is the section appearing; later ones are a refresh. */
    suspend fun ensureLoaded() {
        if (state.repos.isNotEmpty() || loading) return
        load()
    }

    suspend fun load() {
        if (loading) return
        loading = true
        state = state.copy(loading = true, error = null)
        try {
            val text = http.get("$MANGA_SERVER/manga/sources.json").bodyAsText()
            val answer = sourcesJson.decodeFromString<MangaSourcesResponse>(text)
            state = MangaSourcesState(
                loading = false,
                repos = answer.repos,
                sources = answer.sources,
            )
        } catch (e: Exception) {
            state = state.copy(loading = false, error = messageOf(e))
        } finally {
            loading = false
        }
    }

    /**
     * Install a repository. Null means it landed; a string is the sentence the
     * dialog shows, which is the server's own when it has one.
     */
    suspend fun add(url: String): String? = write("add") {
        http.get("$MANGA_SERVER/manga/sources/add.json") {
            parameter("url", url)
        }
    }

    suspend fun remove(url: String): String? = write(url) {
        http.get("$MANGA_SERVER/manga/sources/remove.json") {
            parameter("url", url)
        }
    }

    suspend fun refreshRepo(url: String): String? = write(url) {
        http.get("$MANGA_SERVER/manga/sources/refresh.json") {
            parameter("url", url)
        }
    }

    suspend fun toggle(id: String, enabled: Boolean): String? = write(id) {
        http.get("$MANGA_SERVER/manga/sources/toggle.json") {
            parameter("id", id)
            parameter("on", if (enabled) 1 else 0)
        }
    }

    /**
     * One repository's whole index, when the reader opens it.
     *
     * Fetched on demand rather than with the shelf: a Mihon repository can hold
     * 1,400 extensions, and the settings page only ever draws the counts.
     */
    suspend fun open(url: String): RepoOpen = try {
        val response = http.get("$MANGA_SERVER/manga/sources/repo.json") {
            parameter("url", url)
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) RepoOpen(error = refusal(text, response.status.value))
        else RepoOpen(detail = sourcesJson.decodeFromString<MangaRepoDetail>(text))
    } catch (e: Exception) {
        RepoOpen(error = messageOf(e))
    }

    /**
     * The switch on an extension: every source it provides, on or off.
     *
     * The repository answers with its own refreshed detail, which is what the
     * picker draws next; the shelf is re-read after because the counts on the
     * row behind the dialog changed with it.
     */
    suspend fun toggleExtension(url: String, pkg: String, enabled: Boolean): RepoOpen {
        state = state.copy(busy = pkg, error = null)
        return try {
            val response = http.get("$MANGA_SERVER/manga/sources/toggleExtension.json") {
                parameter("url", url)
                parameter("pkg", pkg)
                parameter("on", if (enabled) 1 else 0)
            }
            val text = response.bodyAsText()
            val answer = if (response.status.value !in 200..299) {
                RepoOpen(error = refusal(text, response.status.value))
            } else {
                RepoOpen(detail = sourcesJson.decodeFromString<MangaRepoDetail>(text))
            }
            state = state.copy(busy = null)
            load()
            answer
        } catch (e: Exception) {
            state = state.copy(busy = null)
            RepoOpen(error = messageOf(e))
        }
    }

    private suspend fun write(
        busy: String,
        request: suspend () -> io.ktor.client.statement.HttpResponse,
    ): String? {
        state = state.copy(busy = busy, error = null)
        return try {
            val response = request()
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                state = state.copy(busy = null)
                return refusal(text, response.status.value)
            }
            val answer = sourcesJson.decodeFromString<MangaSourcesResponse>(text)
            state = MangaSourcesState(repos = answer.repos, sources = answer.sources)
            null
        } catch (e: Exception) {
            state = state.copy(busy = null)
            messageOf(e)
        }
    }

    /** The server's message, or a line that at least says what failed. */
    private fun refusal(text: String, status: Int): String {
        val message = runCatching {
            (sourcesJson.parseToJsonElement(text) as? JsonObject)
                ?.get("message")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: "The sources route answered $status."
    }

    private fun messageOf(e: Exception): String {
        val text = e.message?.trim().orEmpty()
        return text.takeIf { it.isNotEmpty() } ?: "The source list could not be read."
    }
}
