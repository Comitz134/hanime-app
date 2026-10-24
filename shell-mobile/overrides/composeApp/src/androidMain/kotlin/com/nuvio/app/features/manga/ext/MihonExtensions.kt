package com.nuvio.app.features.manga.ext

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.DelegateLastClassLoader
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

/**
 * Runs Mihon extension APKs.
 *
 * A Keiyoushi extension is a dex compiled against Mihon's source API. The API's
 * classes live in `eu.kanade.tachiyomi.*` in this module, so the extension links
 * against this app the way it links against Mihon; the dex is loaded from the
 * APK's own file with a delegate-last classloader, which is also how Mihon does
 * it. Everything the extension needs besides its own classes - OkHttp, jsoup,
 * Injekt, androidx.preference - is a dependency of this app.
 *
 * The class of the source is named in the APK's manifest (`tachiyomi.extension.class`),
 * a `SourceFactory` these days, which is what [sourcesFor] instantiates.
 */
object MihonExtensions {

    private const val META_SOURCE_CLASS = "tachiyomi.extension.class"

    private val mutex = Mutex()
    private val loaded = HashMap<String, Map<Long, Source>>()
    private lateinit var appContext: Context

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * What an extension parses an answer with.
     *
     * An extension does not build its own parser: it asks Injekt for the app's
     * `Json`, which is the one thing Mihon exposes that this runtime did not.
     * Without the binding the source's class initialiser throws
     * `InjektionException`, and the class stays broken for the rest of the
     * process - the next visit answers `NoClassDefFoundError: <source>`. The
     * options are Mihon's own (`AppBindings.providesJson`): an extension may
     * send a field that means nothing here, and a null must not erase a
     * default.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Volatile
    private var installed = false

    /** Called from the manga engine's start-up, before any source is used. */
    @JvmStatic
    fun install(context: Context, versionCode: Int, versionName: String) {
        synchronized(this) {
            if (installed) return
            installed = true
            val app = context.applicationContext
            appContext = app
            NetworkHelper.install(app)
            AppInfo.install(versionCode, versionName)
            // keiyoushi.utils.applicationContext is `Injekt.get<Application>()`,
            // and a generated source asks for the context the same way.
            if (app is Application) Injekt.addSingleton<Application>(app)
            Injekt.addSingleton<Context>(app)
            Injekt.addSingleton<Json>(json)
        }
    }

    // ------------------------------------------------------------- the routes

    /** `/manga/search.json` for one extension source: a page of cards. */
    @JvmStatic
    fun search(
        apk: String,
        pkg: String,
        sourceId: Long,
        query: String,
        page: Int,
        limit: Int,
    ): String = guarded {
        runBlocking {
            val source = sourceFor(apk, pkg, sourceId)
            val result = withContext(Dispatchers.IO) {
                if (query.isBlank()) source.getPopularManga(page) else source.getSearchManga(page, query, source.getFilterList())
            }
            val items = JSONArray()
            for (manga in result.mangas.take(if (limit > 0) limit else result.mangas.size)) {
                items.put(card(manga))
            }
            JSONObject().put("items", items).put("hasNext", result.hasNextPage).toString()
        }
    }

    /** `/manga/title.json` for one extension source. */
    @JvmStatic
    fun title(apk: String, pkg: String, sourceId: Long, url: String): String = guarded {
        runBlocking {
            val source = sourceFor(apk, pkg, sourceId)
            withContext(Dispatchers.IO) {
                val manga = source.mangaFor(url)
                val answer = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
                card(answer.manga).toString()
            }
        }
    }

    /** `/manga/chapters.json` for one extension source. */
    @JvmStatic
    fun chapters(apk: String, pkg: String, sourceId: Long, url: String): String = guarded {
        runBlocking {
            val source = sourceFor(apk, pkg, sourceId)
            withContext(Dispatchers.IO) {
                val manga = source.mangaFor(url)
                val answer = source.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)
                val out = JSONArray()
                for (chapter in answer.chapters.take(2000)) {
                    out.put(
                        JSONObject()
                            .put("id", chapter.url)
                            .put("number", chapterNumber(chapter))
                            .put("name", chapter.name)
                            .put("at", when (chapter.date_upload) {
                                0L -> ""
                                else -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(chapter.date_upload))
                            })
                            .put("created", if (chapter.date_upload > 0) chapter.date_upload / 1000 else 0L),
                    )
                }
                JSONObject().put("chapters", out).toString()
            }
        }
    }

    /** `/manga/pages.json` for one extension source. */
    @JvmStatic
    fun pages(apk: String, pkg: String, sourceId: Long, url: String): String = guarded {
        runBlocking {
            val source = sourceFor(apk, pkg, sourceId)
            withContext(Dispatchers.IO) {
                val chapter = SChapter.create().apply {
                    this.url = url
                    this.name = ""
                }
                val out = JSONArray()
                for (page in source.getPageList(chapter).take(1000)) {
                    // An image address is not always the page's own: some extensions
                    // hand back a path and keep the host elsewhere, which is what
                    // imageUrlFor resolves through the request they would build.
                    val link = (source as? HttpSource)?.imageUrlFor(page)
                        ?: page.imageUrl
                        ?: page.url
                    if (link.startsWith("http://") || link.startsWith("https://")) out.put(link)
                }
                JSONObject().put("pages", out).toString()
            }
        }
    }

    /** Whether an extension can be opened at all; used to name a failure in settings. */
    @JvmStatic
    fun probe(apk: String, pkg: String, sourceId: Long): String = guarded {
        runBlocking {
            val source = sourceFor(apk, pkg, sourceId)
            "${source.name} (${(source as? CatalogueSource)?.lang.orEmpty()})"
        }
    }

    /**
     * Runs one extension call, and turns every failure into an exception.
     *
     * An extension is somebody else's bytecode, compiled against a source API
     * this app only imitates. One built against a newer API can fail to link,
     * and a `LinkageError` or a bare `Error` is not an `Exception`: the route
     * that asked would let it past its catch and Android would end the whole
     * process over a source the reader can simply switch off. A failed source
     * is a row in `errors` instead. Only the errors that mean this app is out
     * of memory or dying are passed through untouched.
     */
    private inline fun <T> guarded(call: () -> T): T = try {
        call()
    } catch (fatal: VirtualMachineError) {
        throw fatal
    } catch (failed: Throwable) {
        throw IllegalStateException(rootMessage(failed), failed)
    }

    // ------------------------------------------------------------- loading it

    private suspend fun sourceFor(apk: String, pkg: String, sourceId: Long): Source {
        val sources = sourcesFor(apk, pkg)
        return sources[sourceId] ?: throw IllegalStateException(
            "The extension $pkg no longer offers that source. Refresh the repository and pick it again.",
        )
    }

    private suspend fun sourcesFor(apk: String, pkg: String): Map<Long, Source> {
        if (!::appContext.isInitialized) throw IllegalStateException("The extension runtime is not installed.")
        loaded[pkg]?.let { return it }
        return mutex.withLock {
            loaded[pkg] ?: load(apk, pkg).also { loaded[pkg] = it }
        }
    }

    private fun load(apk: String, pkg: String): Map<Long, Source> {
        val file = apkFile(apk, pkg)
        if (!file.isFile || file.length() == 0L) download(apk, file)
        val path = file.absolutePath

        val flags = PackageManager.GET_META_DATA or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val info = appContext.packageManager.getPackageArchiveInfo(path, flags)
            ?: throw IllegalStateException("The extension file is not an Android package.")
        val application = info.applicationInfo
            ?: throw IllegalStateException("The extension package has no application.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.sourceDir = path
            application.publicSourceDir = path
        }
        val className = application.metaData?.getString(META_SOURCE_CLASS)
            ?: throw IllegalStateException("The extension does not name a source class.")

        val loader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            DelegateLastClassLoader(path, null, javaClass.classLoader)
        } else {
            PathClassLoaderCompat.create(path, javaClass.classLoader)
        }

        val sources = ArrayList<Source>()
        for (name in className.split(";")) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) continue
            val full = if (trimmed.startsWith(".")) info.packageName + trimmed else trimmed
            val instance = try {
                Class.forName(full, false, loader).getDeclaredConstructor().newInstance()
            } catch (failed: Throwable) {
                throw IllegalStateException("${rootMessage(failed)} ($full)", failed)
            }
            when (instance) {
                is SourceFactory -> sources.addAll(instance.createSources())
                is Source -> sources.add(instance)
                else -> throw IllegalStateException("The extension's source class is not a source ($full).")
            }
        }
        if (sources.isEmpty()) throw IllegalStateException("The extension contains no sources.")
        return sources.associateBy { it.id }
    }

    private fun apkFile(url: String, pkg: String): File {
        val dir = File(appContext.filesDir, "extensions")
        if (!dir.isDirectory) dir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val tag = digest.take(6).joinToString("") { "%02x".format(it) }
        return File(dir, "${pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")}-$tag.apk")
    }

    private fun download(url: String, destination: File) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalStateException("The repository did not give a download address for this extension.")
        }
        val request = Request.Builder().url(url).header("User-Agent", NetworkHelper.USER_AGENT).build()
        val temp = File(destination.parentFile, destination.name + ".part")
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Downloading the extension answered HTTP ${response.code}.")
            val body = response.body
            val length = body.contentLength()
            if (length > 32L * 1024 * 1024) throw IllegalStateException("That extension is too large to install.")
            temp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (temp.length() == 0L) {
            temp.delete()
            throw IllegalStateException("The extension download was empty.")
        }
        if (destination.exists()) destination.delete()
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        destination.setReadOnly()
    }

    // --------------------------------------------------------------- shaping

    private fun card(manga: SManga): JSONObject {
        val out = JSONObject()
            .put("id", manga.url)
            .put("title", manga.title)
        manga.thumbnail_url?.takeIf { it.isNotBlank() }?.let { out.put("poster", it) }
        manga.artist?.takeIf { it.isNotBlank() }?.let { out.put("artist", it) }
        manga.author?.takeIf { it.isNotBlank() }?.let { out.put("author", it) }
        manga.description?.takeIf { it.isNotBlank() }?.let { out.put("description", it) }
        manga.genre?.takeIf { it.isNotBlank() }?.let { genre ->
            val names = JSONArray()
            genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { names.put(it) }
            if (names.length() > 0) out.put("genres", names)
        }
        status(manga.status)?.let { out.put("status", it) }
        return out
    }

    private fun status(code: Int): String? = when (code) {
        SManga.ONGOING -> "ongoing"
        SManga.COMPLETED -> "completed"
        SManga.LICENSED -> "licensed"
        SManga.PUBLISHING_FINISHED -> "completed"
        SManga.CANCELLED -> "cancelled"
        SManga.ON_HIATUS -> "hiatus"
        else -> null
    }

    private fun chapterNumber(chapter: SChapter): String {
        val number = chapter.chapter_number
        if (number < 0f) return ""
        val whole = number.toInt()
        return if (number == whole.toFloat()) whole.toString()
        else String.format(Locale.US, "%.1f", number).trimEnd('0').trimEnd('.')
    }

    private suspend fun Source.mangaFor(url: String): SManga = SManga.create().apply {
        this.url = url
        this.title = url.substringAfterLast('/').ifBlank { url }
    }

    private fun rootMessage(failed: Throwable): String {
        var cause: Throwable = failed
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return listOfNotNull(cause.javaClass.simpleName, cause.message).joinToString(": ")
    }
}

/**
 * The pre-API-27 path. Kept in one place so the runtime can compile against one
 * classloader API and still open an extension on the oldest phone this app runs
 * on.
 */
private object PathClassLoaderCompat {
    fun create(path: String, parent: ClassLoader): ClassLoader =
        dalvik.system.PathClassLoader(path, parent)
}
