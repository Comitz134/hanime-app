package com.nuvio.app.features.mal

import android.content.Context
import android.content.SharedPreferences

/**
 * The android actual, so the multiplatform module compiles whole.
 *
 * This fork ships the desktop app; the android target keeps a store so a
 * build of it behaves the same everywhere — linked accounts persist in the
 * app's own private preferences. The desktop app is where this feature is
 * used and tested.
 */
internal actual object PlatformMalAuthPersistence : MalAuthPersistence {

    private const val PREFS_NAME = "hanime_mal_auth"
    private const val KEY_PREFIX = "profile."

    @Volatile
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun read(profileId: Int): String? =
        preferences?.getString("$KEY_PREFIX$profileId", null)

    override fun write(profileId: Int, value: String?) {
        val editor = preferences?.edit() ?: return
        if (value == null) editor.remove("$KEY_PREFIX$profileId")
        else editor.putString("$KEY_PREFIX$profileId", value)
        editor.apply()
    }

    override fun clear() {
        preferences?.edit()?.clear()?.apply()
    }
}

/**
 * The journal, in preferences of its own beside the tokens. Kept whole on
 * purpose: this fork ships the desktop app, and a store that behaves the same
 * everywhere is what keeps the module compiling for both targets.
 */
internal actual object PlatformMalJournalPersistence : MalJournalPersistence {

    private const val PREFS_NAME = "hanime_mal_activity"
    private const val KEY_PREFIX = "profile."

    @Volatile
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun read(profileId: Int): String? =
        preferences?.getString("$KEY_PREFIX$profileId", null)

    override fun write(profileId: Int, value: String?) {
        val editor = preferences?.edit() ?: return
        if (value == null) editor.remove("$KEY_PREFIX$profileId")
        else editor.putString("$KEY_PREFIX$profileId", value)
        editor.apply()
    }

    override fun clear() {
        preferences?.edit()?.clear()?.apply()
    }
}
