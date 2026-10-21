package com.nuvio.app.features.manga

/**
 * When the next chapter is due, read off the title's own rhythm.
 *
 * mangafire says nothing about the future: there is no schedule field, only the
 * chapters already posted and the date each one went up. What a serialised title
 * does have is a rhythm, and a rhythm projected forward is the only honest answer
 * available — so the estimate is the median gap between the recent chapters
 * applied to the newest one.
 *
 * This is the desktop client's `manga-eta.js` ported rather than reinvented: the
 * tile says the same thing in both, and the three refusals are the same three.
 * No DOM, no storage, no network — `now` is a parameter — because the arithmetic
 * is the part that can be wrong, so it is the part a JVM test can hold.
 *
 *   * a title that has stopped is not projected — its status says so, and the
 *     tile reads N/A instead;
 *   * a title with too few dated chapters has no rhythm to read; two chapters a
 *     year apart are a fact, not a cadence;
 *   * a "cadence" faster than a couple of hours or slower than a year is a
 *     re-upload or a gap in the scanlation, not a schedule.
 */
internal data class MangaEta(
    /** The estimated moment, in epoch milliseconds. */
    val at: Long,
    /** The gap between chapters the estimate was taken from, in milliseconds. */
    val cadence: Long,
    /** How long ago the estimate passed; positive once it is overdue. */
    val late: Long,
) {

    /** The arithmetic, kept beside the value it produces. */
    internal companion object {

        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L

        /** The catalogue's own statuses for "nobody posts another chapter". */
        private val STOPPED = setOf(
            "finished",
            "completed",
            "complete",
            "on_hiatus",
            "hiatus",
            "discontinued",
            "cancelled",
            "canceled",
            "dropped",
            "axed",
        )

        /** How many gaps make a rhythm worth reading; a lazy week is not one. */
        private const val WANTED = 3

        /** How many recent chapters to read the rhythm from. */
        private const val SPAN = 6

        /** The shortest and longest gap that is a schedule, not an accident. */
        private const val MIN_GAP_S = 2 * 3600L
        private const val MAX_GAP_S = 365L * 86_400L

        /** Whether the title's own status says nothing more will be posted. */
        fun hasStopped(status: String): Boolean =
            STOPPED.contains(status.trim().lowercase())

        /**
         * The next chapter, or null when this title cannot be estimated.
         *
         * [chapters] may arrive in any order; only chapters that carry a posting
         * date count, and only the newest [SPAN] of them.
         */
        fun estimate(
            chapters: List<MangaChapter>,
            status: String = "",
            now: Long = currentTimeMillis(),
        ): MangaEta? {
            if (hasStopped(status)) return null

            val dates = chapters
                .map { it.created }
                .filter { it > 0L }
                .sortedDescending()
                .take(SPAN)
            if (dates.size < WANTED + 1) return null

            val gaps = dates.zipWithNext { newer, older -> newer - older }
                .filter { it > 0L }
            if (gaps.size < WANTED) return null

            val cadence = median(gaps)
            if (cadence < MIN_GAP_S || cadence > MAX_GAP_S) return null

            val at = (dates.first() + cadence) * 1000L
            return MangaEta(at = at, cadence = cadence * 1000L, late = now - at)
        }

        /** The estimate in the few characters a tile can hold: `≈3d`, `≈9h`. */
        fun chip(eta: MangaEta?, now: Long = currentTimeMillis()): String {
            if (eta == null) return "N/A"
            val left = eta.at - now
            if (left <= 0L) return "Any day"
            if (left < 90 * 60_000L) return "≈1h"
            // Hours only while there is less than a day of them: "≈46h" is a
            // number the reader has to divide, where "≈2d" is the answer to
            // "when".
            if (left < DAY_MS) return "≈${(left + HOUR_MS / 2) / HOUR_MS}h"
            if (left < 60 * DAY_MS) return "≈${(left + DAY_MS / 2) / DAY_MS}d"
            return "≈${(left + 15 * DAY_MS) / (30 * DAY_MS)}mo"
        }

        /** The same estimate as a sentence, for the tile's own label. */
        fun describe(eta: MangaEta?, now: Long = currentTimeMillis()): String {
            if (eta == null) {
                return "No estimate — this title has no chapter rhythm to read."
            }
            if (eta.late >= 0L) {
                return "Any day now — the next chapter is overdue, on a rhythm of " +
                    "about " + cadenceText(eta.cadence) + "."
            }
            return "Estimated around ${civilDate(eta.at)} — about " +
                cadenceText(eta.cadence) + " between chapters."
        }

        /** The day and the rhythm, for a details row: `2026-10-23 · every 7 days`. */
        fun detail(eta: MangaEta?): String {
            if (eta == null) return ""
            return "${civilDate(eta.at)} · " + cadenceText(eta.cadence)
        }

        private fun cadenceText(cadence: Long): String = when {
            cadence >= 2 * DAY_MS -> "every ${(cadence + DAY_MS / 2) / DAY_MS} days"
            cadence >= DAY_MS -> "every day"
            else -> "every ${maxOf(1L, (cadence + HOUR_MS / 2) / HOUR_MS)} hours"
        }

        private fun median(values: List<Long>): Long {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid]
            else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}

/**
 * The UTC day of a moment, as `2026-10-20`.
 *
 * Written out rather than reached for: this module has no date library, and the
 * one thing it needs is the civil date of an instant — a timezone library to
 * print ten characters would be the tail wagging the dog. The algorithm is the
 * standard days-to-civil one (Hinnant's), and it is tested.
 */
internal fun civilDate(millis: Long): String {
    if (millis <= 0L) return ""
    val z = millis / 86_400_000L + 719_468L
    val era = (if (z >= 0L) z else z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153L
    val d = doy - (153L * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return buildString {
        append(year)
        append('-')
        if (m < 10) append('0')
        append(m)
        append('-')
        if (d < 10) append('0')
        append(d)
    }
}

/** The clock, in one place so that the estimate stays a pure function. */
internal fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
