package eu.kanade.tachiyomi

/**
 * Version of the host application, as extensions see it.
 *
 * Extensions put this in a User-Agent string; the numbers are the app's own, set
 * once by the manga runtime when it starts.
 */
object AppInfo {

    @Volatile
    private var versionCode: Int = 1

    @Volatile
    private var versionName: String = "2.0.0"

    fun install(code: Int, name: String) {
        versionCode = code
        versionName = name
    }

    fun getVersionCode(): Int = versionCode

    fun getVersionName(): String = versionName
}
