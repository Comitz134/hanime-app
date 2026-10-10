package app.hanime.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The server this app is a window onto.
 *
 * The catalogue, the episode lists and every resolved stream come from
 * `../server` — the same Node routes the web client and the app shell answer —
 * so this file holds no upstream knowledge of its own. Point it somewhere else
 * with `-Dhanime.server=` or `HANIME_SERVER=` and the whole app follows.
 */
object Api {

    val base: String = (System.getProperty("hanime.server")
        ?: System.getenv("HANIME_SERVER")
        ?: "http://127.0.0.1:8787").trimEnd('/')

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** GET a route, as text. Throws with the status when the server refuses. */
    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(45))
            .header("accept", "application/json")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("$path answered ${response.statusCode()}")
        }
        response.body()
    }

    /** GET a route as JSON, or null when anything at all went wrong. */
    suspend fun getJson(path: String): JsonObject? = try {
        json.parseToJsonElement(get(path)).jsonObject
    } catch (e: Exception) {
        null
    }

    /** Is the server up? Answered without throwing, for the status dot. */
    suspend fun alive(): Boolean = try {
        getJson("/api/videos?per_page=1") != null
    } catch (e: Exception) {
        false
    }

    // --------------------------------------------------------------- reading

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.num(key: String): Double? = str(key)?.toDoubleOrNull()

    private fun JsonObject.list(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    // ------------------------------------------------------------ the areas

    /**
     * The 18+ catalogue. `order_by` is the server's own field name; the web
     * client's shelves use `views` for "most viewed" and `released_at` for
     * "newest", so the rails here read the same way.
     */
    suspend fun videos(
        page: Int = 1,
        perPage: Int = 24,
        orderBy: String = "released_at",
        ordering: String = "desc",
        q: String? = null,
        brand: String? = null,
        tag: String? = null,
    ): List<Title> {
        val params = buildString {
            append("?page=$page&per_page=$perPage&order_by=$orderBy&ordering=$ordering")
            q?.let { append("&q=" + enc(it)) }
            brand?.let { append("&brand=" + enc(it)) }
            tag?.let { append("&tag=" + enc(it)) }
        }
        val body = getJson("/api/videos$params") ?: return emptyList()
        return body.list("data").map { row ->
            Title(
                ref = "video/" + (row.str("slug") ?: return@map null),
                kind = Kind.VIDEO,
                title = row.str("name") ?: "Untitled",
                year = row.str("released_at")?.take(4),
                score = null,
                poster = row.str("cover"),
                subtitle = row.str("brand"),
            )
        }.filterNotNull()
    }

    suspend fun animeSearch(
        q: String? = null,
        page: Int = 1,
        genre: String? = null,
        format: String? = null,
        status: String? = null,
    ): Pair<List<Title>, Boolean> {
        val params = buildString {
            append("?page=$page")
            q?.takeIf { it.isNotBlank() }?.let { append("&q=" + enc(it)) }
            genre?.let { append("&genre=" + enc(it)) }
            format?.let { append("&format=" + enc(it)) }
            status?.let { append("&status=" + enc(it)) }
        }
        val body = getJson("/api/anime/search$params") ?: return emptyList<Title>() to false
        val items = body.list("items").map { row ->
            val id = row.str("id") ?: return@map null
            Title(
                ref = "anime/$id",
                kind = Kind.ANIME,
                title = row.str("title") ?: "Untitled",
                year = row.str("year"),
                score = row.num("score")?.let { if (it > 10) it / 10.0 else it },
                poster = row.str("cover"),
                backdrop = row.str("banner"),
                subtitle = row.str("format"),
            )
        }.filterNotNull()
        return items to (body.str("hasNext")?.toBooleanStrictOrNull() ?: false)
    }

    suspend fun filmsSearch(
        q: String? = null,
        page: Int = 1,
        type: String? = null,
    ): Pair<List<Title>, Boolean> {
        val params = buildString {
            append("?page=$page")
            q?.takeIf { it.isNotBlank() }?.let { append("&q=" + enc(it)) }
            type?.takeIf { it.isNotBlank() }?.let { append("&type=" + enc(it)) }
        }
        val body = getJson("/api/fmovies/search$params") ?: return emptyList<Title>() to false
        val items = body.list("items").map { row ->
            val slug = row.str("slug") ?: return@map null
            val kind = if (row.str("type") == "tv") Kind.SERIES else Kind.FILM
            Title(
                ref = "${row.str("type") ?: "movie"}/$slug",
                kind = kind,
                title = row.str("title") ?: "Untitled",
                year = row.str("year"),
                score = row.num("score"),
                poster = row.str("poster"),
                subtitle = if (kind == Kind.SERIES) "Series" else "Film",
            )
        }.filterNotNull()
        return items to (body.str("hasNext")?.toBooleanStrictOrNull() ?: false)
    }

    suspend fun brands(): List<Pair<String, Int>> =
        (getJson("/api/brands")?.list("data") ?: emptyList()).mapNotNull { row ->
            val name = row.str("name") ?: return@mapNotNull null
            name to (row.str("count")?.toIntOrNull() ?: 0)
        }

    // ------------------------------------------------------------- one title

    /** Everything a detail page needs, in whichever area the ref names. */
    suspend fun detail(ref: String): Detail? = when (ref.substringBefore('/')) {
        "anime" -> animeDetail(ref.substringAfter('/'))
        "movie", "tv" -> filmDetail(ref)
        "video" -> videoDetail(ref.substringAfter('/'))
        else -> null
    }

    private suspend fun animeDetail(id: String): Detail? {
        val body = getJson("/api/anime/$id") ?: return null
        val title = body.jsonObject["title"]?.let { (it as? JsonObject) } ?: body
        val row = title
        return Detail(
            ref = "anime/$id",
            kind = Kind.ANIME,
            title = row.str("romaji") ?: row.str("english") ?: row.str("native") ?: "Untitled",
            year = row.str("startDate")?.let { seasonYear(it) },
            score = row.num("averageScore")?.let { if (it > 10) it / 10.0 else it },
            poster = row.str("cover"),
            backdrop = row.str("banner"),
            synopsis = stripTags(row.str("description")),
            genres = (row["genres"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            cast = (row["studios"] as? JsonArray)?.mapNotNull { s ->
                ((s as? JsonObject)?.str("name"))
            } ?: emptyList(),
            seasons = emptyList(),
            episodes = emptyList(),
        )
    }

    private fun seasonYear(start: String): String =
        Regex("(\\d{4})").find(start)?.value ?: start

    private suspend fun videoDetail(slug: String): Detail? {
        val row = getJson("/api/videos/" + enc(slug))?.jsonObject ?: return null
        return Detail(
            ref = "video/$slug",
            kind = Kind.VIDEO,
            title = row.str("name") ?: "Untitled",
            year = row.str("released_at")?.take(4),
            score = null,
            poster = row.str("cover"),
            backdrop = row.str("poster"),
            synopsis = stripTags(row.str("description")),
            genres = (row["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            cast = listOfNotNull(row.str("brand")),
            seasons = emptyList(),
            episodes = emptyList(),
        )
    }

    private suspend fun filmDetail(ref: String): Detail? {
        val type = ref.substringBefore('/')
        val slug = ref.substringAfter('/')
        val body = getJson("/api/fmovies/$type/" + enc(slug))?.jsonObject ?: return null
        val row = (body["details"] as? JsonObject) ?: body
        val seasons = (row["seasons"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
            ?: emptyList()
        return Detail(
            ref = ref,
            kind = if (type == "tv") Kind.SERIES else Kind.FILM,
            title = row.str("title") ?: "Untitled",
            year = row.str("year"),
            score = row.num("score"),
            poster = row.str("poster"),
            backdrop = null,
            synopsis = stripTags(row.str("description")),
            genres = (row["genres"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            cast = ((row["cast"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()).take(6),
            seasons = seasons,
            episodes = emptyList(),
        )
    }

    /** What plays, and the episode rows of a series — resolved on demand. */
    suspend fun episodes(ref: String, season: Int = 1): List<Episode> {
        val type = ref.substringBefore('/')
        val slug = ref.substringAfter('/')
        return when (type) {
            "anime" -> {
                val body = getJson("/api/anime/$slug/episodes") ?: return emptyList()
                body.list("data").mapNotNull { row ->
                    val n = row.str("episode")?.toIntOrNull() ?: return@mapNotNull null
                    Episode(n, row.str("title") ?: "Episode $n")
                }.sortedBy { it.number }
            }
            "tv" -> {
                val body = getJson("/api/fmovies/tv/" + enc(slug) + "/episodes?season=$season")
                    ?: return emptyList()
                body.list("data").mapNotNull { row ->
                    val n = row.str("n")?.toIntOrNull() ?: row.str("episode")?.toIntOrNull()
                        ?: return@mapNotNull null
                    Episode(n, row.str("title") ?: "Episode $n")
                }.sortedBy { it.number }
            }
            else -> emptyList()
        }
    }

    /** The URL to hand a player: an HLS source, or the films area's own door. */
    suspend fun streamUrl(ref: String, episode: Int = 1): String? {
        val type = ref.substringBefore('/')
        val id = ref.substringAfter('/')
        return when (type) {
            "anime" -> {
                val body = getJson("/api/anime/$id/player?ep=$episode")
                (body?.list("sources") ?: emptyList()).firstOrNull()?.str("url")
                    ?: body?.str("url")
            }
            "video" -> {
                val body = getJson("/api/videos/" + enc(id) + "/sources")
                (body?.list("sources") ?: emptyList()).firstOrNull()?.str("url")
            }
            "movie", "tv" -> {
                val body = getJson("/api/fmovies/$type/" + enc(id) + "/player?season=1&episode=$episode")
                (body?.list("sources") ?: emptyList()).firstOrNull { it.str("ok") != "false" }
                    ?.str("url")
                    ?: (body?.list("sources") ?: emptyList()).firstOrNull()?.str("url")
            }
            else -> null
        }
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun stripTags(html: String?): String =
        html?.replace(Regex("<br\\s*/?>"), "\n")
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.replace("&#039;", "'")
            ?.replace("&nbsp;", " ")
            ?.replace(Regex("\\s{2,}"), " ")
            ?.trim()
            ?: ""
}

enum class Kind { ANIME, SERIES, FILM, VIDEO }

/** One poster, from whichever area it came. */
data class Title(
    val ref: String,
    val kind: Kind,
    val title: String,
    val year: String?,
    val score: Double?,
    val poster: String?,
    val backdrop: String? = null,
    val subtitle: String? = null,
)

data class Episode(val number: Int, val name: String)

data class Detail(
    val ref: String,
    val kind: Kind,
    val title: String,
    val year: String?,
    val score: Double?,
    val poster: String?,
    val backdrop: String?,
    val synopsis: String,
    val genres: List<String>,
    val cast: List<String>,
    val seasons: List<Int>,
    val episodes: List<Episode>,
)
