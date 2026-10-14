package com.nuvio.app.features.mal

/**
 * Where this provider keeps its tokens, per app profile.
 *
 * The desktop app stores them beside its other preferences (a properties file
 * under the app's own data directory); the android target keeps no MAL
 * session at all — this fork ships the desktop app, and a token that is never
 * stored there is a token that cannot leak.
 */
internal interface MalAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

internal expect object PlatformMalAuthPersistence : MalAuthPersistence
