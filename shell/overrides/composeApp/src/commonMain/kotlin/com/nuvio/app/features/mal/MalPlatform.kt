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

/**
 * Where the activity journal lives, per app profile.
 *
 * A store of its own rather than a corner of the token file: the tokens are
 * one secret that is cleared on disconnect, and the journal is what the panel
 * is reading when a disconnect is the thing it has to explain. Same shape as
 * [MalAuthPersistence], so the two behave alike and a test can swap either.
 */
internal interface MalJournalPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

internal expect object PlatformMalJournalPersistence : MalJournalPersistence
