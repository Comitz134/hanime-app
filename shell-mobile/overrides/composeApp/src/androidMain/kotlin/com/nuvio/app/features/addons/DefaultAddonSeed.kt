package com.nuvio.app.features.addons

import android.content.Context

/**
 * The content source this build ships with, installed on the first run.
 *
 * The app's catalogues are not built into the shell: they come from the
 * addon server the APK starts on 127.0.0.1:8787, and until a reader adds
 * `http://127.0.0.1:8787/addon/manifest.json` by hand Home says "No content
 * sources" and looks broken. That is what a fresh install did - the reader
 * had to know the loopback URL and where to paste it - so the first launch
 * seeds the same row the Addons screen would have written.
 *
 * Seeded into [AddonStorage] rather than through [AddonRepository.addAddon],
 * because storage is read before the first frame: the manifest is fetched by
 * the repository's own refresh, which is also what happens when the row is
 * added by hand. That keeps the seed free of network access, so a phone with
 * no connection still shows the addon (and its load error, which says more
 * than an empty Home does).
 *
 * Once only: the flag is written before the list is read, so removing the
 * addon later is a decision the app keeps, and a reader who already has
 * addons of their own on the first run of this build is left alone.
 */
internal object DefaultAddonSeed {
    /** The manifest SekaiServer answers; the row the desktop writes too. */
    const val ManifestUrl = "http://127.0.0.1:8787/addon/manifest.json"

    /** Where the "we already did this" flag lives. Not the addon's own file. */
    private const val preferencesName = "sekai_addon_seed"
    private const val seededKey = "default_addon_seeded"

    /** Addons are stored per profile, and a first run only has the first one. */
    private const val primaryProfileId = 1

    fun seedIfNeeded(context: Context) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        if (preferences.getBoolean(seededKey, false)) return
        // Written first: if writing the list somehow does not stick, adding it
        // again on every launch would be the worse failure.
        preferences.edit().putBoolean(seededKey, true).apply()

        if (AddonStorage.loadInstalledAddonUrls(primaryProfileId).isNotEmpty()) return
        AddonStorage.saveInstalledAddonUrls(primaryProfileId, listOf(ManifestUrl))
        AddonStorage.saveAddonEnabledStates(
            primaryProfileId,
            AddonStorage.loadAddonEnabledStates(primaryProfileId) + (ManifestUrl to true),
        )
    }
}
