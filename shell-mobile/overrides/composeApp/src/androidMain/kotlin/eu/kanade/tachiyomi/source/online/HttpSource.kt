package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * The base class every Keiyoushi extension extends, implemented here rather than
 * imported: extensions are compiled against Mihon's stubs and resolve these
 * classes from the host app at runtime. The shape matters as much as the code -
 * extensions reach for `headers$delegate` by reflection (see KeiSource), so the
 * property below has to stay a `by lazy` delegate with exactly that name.
 */
abstract class HttpSource : CatalogueSource {

    /** Network service: the client every request goes through. */
    protected val network: NetworkHelper
        get() = NetworkHelper.shared

    /** Base url of the website without the trailing slash, like: http://mysite.com */
    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    /** Headers used for requests. Kept as a delegate field on purpose. */
    val headers: Headers by lazy { headersBuilder().build() }

    /** Default network client for doing requests. */
    open val client: OkHttpClient get() = network.client

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    /**
     * Returns the image url for the provided [page]. Only asked when the page
     * came back without one.
     */
    open suspend fun getImageUrl(page: Page): String = page.imageUrl ?: page.url

    /**
     * The absolute address of a page's image.
     *
     * Not every extension hands one over: MangaDex returns a path and keeps the
     * host in the page's own url, expecting the app to ask [imageRequest] for
     * the final address (which is also where it refreshes an expiring token).
     * So a page whose `imageUrl` is not absolute is resolved through the
     * request the extension itself would make.
     */
    fun imageUrlFor(page: Page): String {
        val direct = page.imageUrl
        if (direct != null && (direct.startsWith("http://") || direct.startsWith("https://"))) return direct
        return try {
            imageRequest(page).url.toString()
        } catch (unbuildable: Exception) {
            direct ?: page.url
        }
    }

    protected open fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl ?: page.url)
        .headers(headers)
        .build()

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig.replace(" ", "%20"))
            val out = StringBuilder(uri.path ?: return orig)
            if (uri.query != null) out.append('?').append(uri.query)
            if (uri.fragment != null) out.append('#').append(uri.fragment)
            out.toString()
        } catch (_: URISyntaxException) {
            orig
        }
    }

    abstract fun getMangaUrl(manga: SManga): String

    abstract fun getChapterUrl(chapter: SChapter): String
}
