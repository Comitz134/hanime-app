package com.nuvio.app.features.nsfw

import android.content.Context
import android.content.SharedPreferences

/**
 * The android actual, so the multiplatform module compiles whole.
 *
 * This fork ships the desktop app; the android target keeps the same answer in
 * the app's own private preferences — a choice that behaves the same on both
 * targets is what keeps the module honest for both.
 */
internal actual object PlatformNsfwPreference : NsfwPreference {

    private const val PREFS_NAME = "hanime_nsfw"
    private const val KEY = "hide"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun read(): Boolean = preferences?.getBoolean(KEY, false) ?: false

    override fun write(hidden: Boolean) {
        preferences?.edit()?.putBoolean(KEY, hidden)?.apply()
    }
}
