package com.nuvio.app.features.nsfw

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the reader's "hide 18+" answer is kept.
 *
 * One boolean, so one store: the desktop actual writes it beside the app's
 * other preferences, the android actual in the app's own preferences. A test
 * swaps in an in-memory store, exactly as the MAL token and journal stores do.
 */
internal interface NsfwPreference {
    fun read(): Boolean
    fun write(hidden: Boolean)
}

internal expect object PlatformNsfwPreference : NsfwPreference

/**
 * Whether this app should offer adult content at all.
 *
 * The catalogue this window shows is one source with three areas — anime,
 * films and series, and the 18+ shelves — and someone who does not want the
 * last one in their home rails should not have to curate it shelf by shelf in
 * the homescreen settings. The choice lands here and is applied in exactly one
 * place ([com.nuvio.app.features.addons.enabledAddons]), which is the seam
 * every surface that reads the catalogue goes through: home rails, search,
 * discover, catalog pages and collections all ask that one function, so a
 * shelf hidden here is hidden in all of them rather than in whichever ones
 * remembered to filter.
 *
 * It is a *view* filter, not a deletion: nothing is removed from the addon,
 * the reader's own list, their history, or the titles they already saved —
 * turning the option off brings the shelves straight back, and an adult title
 * opened from the library still plays.
 */
internal object NsfwContent {

    /** The platform store in production; a test replaces it with memory. */
    internal var store: NsfwPreference = PlatformNsfwPreference

    private val _hidden = MutableStateFlow(false)

    /** What the settings switch reads, and what a toggle repaints. */
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    private var loaded = false

    /** The answer the catalogue's own filter asks for, without a subscription. */
    fun isHidden(): Boolean {
        ensureLoaded()
        return _hidden.value
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _hidden.value = runCatching { store.read() }.getOrDefault(false)
    }

    fun setHidden(hidden: Boolean) {
        loaded = true
        if (_hidden.value == hidden) return
        _hidden.value = hidden
        runCatching { store.write(hidden) }
    }

    /**
     * Drops the answer held in memory so the store behind it is read again —
     * what a test does after putting a store of its own in place.
     */
    fun forget() {
        loaded = false
        _hidden.value = false
    }
}

/**
 * One addon as the catalogue's readers see it when the reader has asked for
 * no adult content: the same addon with its adult shelves taken out.
 *
 * Only `catalogs` is touched. `types` stays whole on purpose — an adult title
 * in the reader's own library is a `movie` (the 18+ entries have always been
 * filed that way), so meta, streams and playback resolve exactly as before and
 * the switch cannot make something the reader already saved unopenable.
 */
internal fun ManagedAddon.withoutAdultCatalogs(): ManagedAddon {
    val current = manifest ?: return this
    val kept = current.catalogs.filterNot { catalog ->
        catalog.isAdultShelf(addonIsAdult = current.behaviorHints.adult)
    }
    if (kept.size == current.catalogs.size) return this
    return copy(manifest = current.copy(catalogs = kept))
}

/**
 * The shelf types this project's source files adult content under — `hentai`
 * is its own (`server/src/addon.mjs` declares the two 18+ shelves with it) —
 * plus the usual spellings a third-party source may use. An addon that
 * declares itself adult has every shelf filed here, which is what
 * `behaviorHints.adult` means: a source that is nothing but adult content.
 */
private val ADULT_CATALOG_TYPES = setOf("hentai", "adult")

private fun AddonCatalog.isAdultShelf(addonIsAdult: Boolean): Boolean =
    addonIsAdult || type.trim().lowercase() in ADULT_CATALOG_TYPES
