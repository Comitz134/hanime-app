package com.nuvio.app.features.nsfw

import com.nuvio.app.core.storage.DesktopStorage

/**
 * The desktop actual: one boolean beside the app's other preferences, in the
 * app's own data directory — the same store the addons, the MAL session and
 * the rest of the window's settings live in, so a self-update carries the
 * choice across.
 *
 * Absent means "not hidden": a fresh install offers the whole catalogue, and
 * the switch is the reader's to turn on.
 */
internal actual object PlatformNsfwPreference : NsfwPreference {

    private const val STORE_NAME = "nsfw"
    private const val KEY = "hide"

    private val store = DesktopStorage.store(STORE_NAME)

    override fun read(): Boolean = store.getBoolean(KEY) ?: false

    override fun write(hidden: Boolean) {
        store.putBoolean(KEY, hidden)
    }
}
