package com.nuvio.app.features.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A title one tab hands to another.
 *
 * The Library tab draws the reader's manga shelf, but the reader itself lives
 * in the manga tab, so a shelf row there cannot open anything by itself: it
 * says what was tapped here and asks the app to select the manga tab, which
 * reads this on the way in and opens what it finds. One slot, consumed once —
 * a request that was answered must not re-open the title the next time the tab
 * is drawn.
 */
internal object MangaNav {

    /** What a shelf row asked for: the title, and the place to resume from. */
    data class Request(
        val id: String,
        val title: String,
        val poster: String,
        val resume: MangaPosition? = null,
    )

    /** A request waiting to be picked up by the manga tab. */
    var request by mutableStateOf<Request?>(null)
        private set

    fun open(request: Request) {
        this.request = request
    }

    fun open(id: String, title: String, poster: String, resume: MangaPosition? = null) {
        if (id.isBlank()) return
        request = Request(id = id, title = title, poster = poster, resume = resume)
    }

    /** The pending request, cleared — the caller is the one who acts on it. */
    fun take(): Request? {
        val pending = request ?: return null
        request = null
        return pending
    }

    /**
     * A title opened from the Library, closed again.
     *
     * The manga tab cannot select another tab — the tab is the destination — so
     * the title that was handed over here names the tab it came from on the way
     * back: "return" out of a Library title lands in the Library, at the shelf
     * the reader was on, rather than in a manga tab they never chose. A counter
     * rather than a flag, so two returns in a row are two events.
     */
    var returnToLibraryRequests by mutableIntStateOf(0)
        private set

    fun requestReturnToLibrary() {
        returnToLibraryRequests++
    }
}
