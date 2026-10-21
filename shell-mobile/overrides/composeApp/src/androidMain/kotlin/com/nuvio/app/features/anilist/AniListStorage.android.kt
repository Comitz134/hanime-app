package com.nuvio.app.features.anilist

import android.content.Context
import android.content.SharedPreferences

/**
 * The AniList session, in the app's own preferences.
 *
 * One account for the whole app, not one per profile: the token is a
 * credential rather than a preference, and a profile switch is not a different
 * person as far as AniList is concerned. The store is private to the app, the
 * same place Trakt's, Simkl's and MyAnimeList's tokens live.
 *
 * The title→media id map rides along with the session: it is what keeps a
 * chapter write from searching for the title again on every page, and it is
 * worthless without the account it was resolved under.
 */
internal actual object AniListStorage {

    private const val PREFS = "sekai_anilist"
    private const val KEY = "session"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    actual fun load(): String? = preferences?.getString(KEY, null)

    actual fun save(payload: String) {
        preferences?.edit()?.putString(KEY, payload)?.apply()
    }

    actual fun clear() {
        preferences?.edit()?.remove(KEY)?.apply()
    }
}
