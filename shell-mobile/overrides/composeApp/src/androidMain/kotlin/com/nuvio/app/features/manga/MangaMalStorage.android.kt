package com.nuvio.app.features.manga

import android.content.Context
import android.content.SharedPreferences

/**
 * The MyAnimeList session, in the app's own preferences.
 *
 * One account for the manga tab, not one per profile: the reader is tracking
 * their own list, and the token is a credential rather than a preference — a
 * profile switch is not a different person as far as MAL is concerned. The
 * store is private to the app, the same place Trakt's and Simkl's tokens live.
 */
internal actual object MangaMalStorage {

    private const val PREFS = "sekai_manga_mal"
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
