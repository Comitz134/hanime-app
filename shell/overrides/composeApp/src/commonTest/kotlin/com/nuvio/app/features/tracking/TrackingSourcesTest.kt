package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.mal.MalTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrackingSourcesTest {
    @Test
    fun `stored legacy source names retain their meaning`() {
        assertEquals(WatchProgressSource.TRAKT, WatchProgressSource.fromStorage("TRAKT"))
        assertEquals(WatchProgressSource.NUVIO_SYNC, WatchProgressSource.fromStorage("NUVIO_SYNC"))
        assertEquals(LibrarySourceMode.TRAKT, librarySourceModeFromStorage("TRAKT"))
    }

    @Test
    fun `remote watch source falls back when its provider is disconnected`() {
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { false },
        )
        assertEquals(
            WatchProgressSource.SIMKL,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { it == TrackingProviderId.SIMKL },
        )
    }

    @Test
    fun `remote library source falls back to local when disconnected`() {
        assertEquals(
            LibrarySourceMode.LOCAL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { false },
        )
        assertEquals(
            LibrarySourceMode.SIMKL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { it == TrackingProviderId.SIMKL },
        )
    }

    @Test
    fun `local sources have no remote provider`() {
        assertNull(WatchProgressSource.NUVIO_SYNC.providerId)
        assertNull(LibrarySourceMode.LOCAL.providerId)
    }

    @Test
    fun `every remote source names a provider`() {
        LibrarySourceMode.entries.forEach { mode ->
            if (mode == LibrarySourceMode.LOCAL) {
                assertNull(mode.providerId, "the local source is not a provider")
            } else {
                assertNotNull(mode.providerId, "$mode must name the provider it reads")
            }
        }
    }

    @Test
    fun `MyAnimeList can be chosen as the library source`() {
        // The shipped bug, pinned: MAL connected and produced shelves, yet
        // nothing could display them — LibrarySourceMode had no MAL entry, so
        // no source option reached the account's list.
        assertEquals(TrackingProviderId.MAL, LibrarySourceMode.MAL.providerId)
        MalTracker.register()
        assertNotNull(
            TrackingProviderRegistry.libraryProvider(TrackingProviderId.MAL),
            "the MAL source must reach the registered MAL library provider",
        )
    }
}
