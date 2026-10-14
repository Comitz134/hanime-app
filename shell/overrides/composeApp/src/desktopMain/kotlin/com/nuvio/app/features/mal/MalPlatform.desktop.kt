package com.nuvio.app.features.mal

import com.nuvio.app.core.storage.DesktopStorage

/**
 * The desktop actual: tokens beside the app's other preferences, in the
 * app's own data directory (`Hanime/…`), one properties file, one key per
 * profile — the same store the rest of the window reads its settings from.
 */
internal actual object PlatformMalAuthPersistence : MalAuthPersistence {

    private const val STORE_NAME = "mal-auth"
    private const val KEY_PREFIX = "profile."

    private val store = DesktopStorage.store(STORE_NAME)

    override fun read(profileId: Int): String? = store.getString("$KEY_PREFIX$profileId")

    override fun write(profileId: Int, value: String?) {
        store.putString("$KEY_PREFIX$profileId", value)
    }

    override fun clear() {
        // The store has no whole-file delete, and profiles are a handful of
        // ids at most; clearing every slot that could hold one is the same
        // result.
        for (profileId in 0 until 32) store.remove("$KEY_PREFIX$profileId")
    }
}
