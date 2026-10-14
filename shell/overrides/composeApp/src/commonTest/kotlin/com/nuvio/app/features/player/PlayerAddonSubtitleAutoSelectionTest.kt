package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.streams.StreamSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player only picks a subtitle by itself when a preferred subtitle language
 * is configured, and that setting starts at "none" — which is how a stream that
 * carries its own subtitle files (the anime area's embeds turn out to list up to
 * ten, in as many languages) would open with the tracks listed and none of them
 * on. This covers the override that turns one on instead, and the cases it must
 * stay out of: a choice the person has already made, and a title already showing
 * a track.
 */
class PlayerAddonSubtitleAutoSelectionTest {
    @Test
    fun theOnlyOfferedTrackIsTurnedOnWhenNoSubtitleLanguageIsPreferred() {
        val controller = RecordingController()
        val runtime = runtime(controller)
        val english = addonSubtitle("https://cdn.example/73b119bb_eng_3.ass", "en", "Dialogues")
        runtime.addonSubtitles = listOf(english)

        runtime.refreshTracks()

        assertEquals(listOf(english.url), controller.subtitleUris)
        assertEquals(english.url, runtime.selectedAddonSubtitleId)
        assertTrue(runtime.useCustomSubtitles)
        assertEquals(-1, runtime.selectedSubtitleIndex)
    }

    @Test
    fun theDeviceLanguageWinsAndEnglishBeatsTheFirstEntry() {
        val controller = RecordingController()
        val runtime = runtime(controller)
        val arabic = addonSubtitle("https://cdn.example/75_eng_2.ass", "ar", "CR_Arabic")
        val english = addonSubtitle("https://cdn.example/75_eng_3.ass", "en", "CR_English")
        runtime.addonSubtitles = listOf(arabic, english)

        runtime.refreshTracks()

        // The provider lists Arabic first, and a preference neither language is
        // part of: English is then the answer, never the entry that came first.
        val devicePrimaries = DeviceLanguagePreferences.preferredLanguageCodes()
            .mapNotNull { normalizeLanguageCode(it)?.substringBefore('-') }
        val deviceOffered = listOf(arabic, english).firstOrNull { track ->
            normalizeLanguageCode(track.language)?.substringBefore('-') in devicePrimaries
        }

        assertEquals(listOf((deviceOffered ?: english).url), controller.subtitleUris)
        assertEquals((deviceOffered ?: english).url, runtime.selectedAddonSubtitleId)
    }

    @Test
    fun aDeviceLanguageWithARegionStillFindsItsTrack() {
        val controller = RecordingController()
        val runtime = runtime(controller)
        // 'en-US' as a device language does not match the track 'en' through the
        // app's own matcher, which keeps regions apart; it has to here, or the
        // answer to an English device would be Arabic.
        val arabic = addonSubtitle("https://cdn.example/75_eng_2.ass", "ar", "CR_Arabic")
        val english = addonSubtitle("https://cdn.example/75_eng_3.ass", "en-US", "CR_English")
        runtime.addonSubtitles = listOf(arabic, english)

        runtime.refreshTracks()

        val devicePrimaries = DeviceLanguagePreferences.preferredLanguageCodes()
            .mapNotNull { normalizeLanguageCode(it)?.substringBefore('-') }
        val expected = if ("en" in devicePrimaries || "ar" in devicePrimaries) {
            listOf(arabic, english).first { normalizeLanguageCode(it.language)?.substringBefore('-') in devicePrimaries }
        } else {
            english
        }

        assertEquals(listOf(expected.url), controller.subtitleUris)
    }

    @Test
    fun aTrackNamedInWordsStillMatchesTheDeviceLanguage() {
        val controller = RecordingController()
        val runtime = runtime(controller)
        // An addon that sends 'English' rather than 'en' — the page's own words,
        // which every client can read and none of them match a preference with.
        val arabic = addonSubtitle("https://cdn.example/75_eng_2.ass", "Arabic", "CR_Arabic")
        val english = addonSubtitle("https://cdn.example/75_eng_3.ass", "English", "CR_English")
        runtime.addonSubtitles = listOf(arabic, english)

        runtime.refreshTracks()

        val devicePrimaries = DeviceLanguagePreferences.preferredLanguageCodes()
            .mapNotNull { normalizeLanguageCode(it)?.substringBefore('-') }
        val expected = if ("en" in devicePrimaries || "ar" in devicePrimaries) {
            listOf(arabic, english).first { normalizeLanguageCode(it.language)?.substringBefore('-') in devicePrimaries }
        } else {
            english
        }

        assertEquals(listOf(expected.url), controller.subtitleUris)
    }

    @Test
    fun anExplicitSubtitleChoiceIsNeverOverridden() {
        val controller = RecordingController()
        val runtime = runtime(controller)
        runtime.addonSubtitles = listOf(
            addonSubtitle("https://cdn.example/75_eng_3.ass", "en", "Dialogues"),
        )
        // Turning subtitles off for this title counts as a choice too.
        runtime.isUserExplicitSubtitleSelection = true

        runtime.refreshTracks()

        assertTrue(controller.subtitleUris.isEmpty())
    }

    @Test
    fun aTrackAlreadyOnScreenIsNotReApplied() {
        val controller = RecordingController()
        val runtime = runtime(controller)
        val english = addonSubtitle("https://cdn.example/75_eng_3.ass", "en", "Dialogues")
        runtime.addonSubtitles = listOf(english)
        runtime.selectedAddonSubtitleId = english.url
        runtime.useCustomSubtitles = true

        runtime.refreshTracks()

        assertTrue(controller.subtitleUris.isEmpty())
    }

    @Test
    fun aDownloadedCopyKeepsItsOwnSidecarSubtitles() {
        val controller = RecordingController()
        val runtime = runtime(controller)
        runtime.addonSubtitles = listOf(
            addonSubtitle("https://cdn.example/75_eng_3.ass", "en", "Dialogues"),
        )
        runtime.activeSourceUrl = "file:///movies/episode-01.mkv"
        runtime.externalSubtitles = listOf(
            StreamSubtitle(
                url = "file:///movies/episode-01.en.srt",
                language = "en",
                name = "Downloaded",
            ),
        )

        runtime.refreshTracks()

        assertTrue(controller.subtitleUris.isEmpty())
    }

    private fun addonSubtitle(url: String, language: String, label: String) = AddonSubtitle(
        id = url.substringAfterLast('/'),
        url = url,
        language = language,
        display = "$label · SEKAI",
        addonName = "SEKAI",
    )

    private fun runtime(controller: RecordingController) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Test title",
            sourceUrl = "http://127.0.0.1:8787/play/anime/195604?ep=1",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Hanime · in-app",
            streamTitle = "HLS",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Episode 1",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "ha:an:195604:1:1",
            parentMetaId = "ha:an:195604",
            parentMetaType = "series",
            providerAddonId = "tv.hanime.bridge",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
            contentLanguage = null,
        ),
    ).apply {
        playerController = controller
        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true)
        playerSettingsUiState = PlayerSettingsUiState(
            preferredSubtitleLanguage = SubtitleLanguageOption.NONE,
        )
        // The title has no stored preference: nothing to restore, and no
        // subtitle language configured at the settings level.
        trackPreferenceRestoreApplied = true
    }

    private class RecordingController(
        private var audio: List<AudioTrack> = emptyList(),
    ) : PlayerEngineController {
        val subtitleUris = mutableListOf<String>()

        override fun getAudioTracks() = audio
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun setSubtitleUri(url: String) {
            subtitleUris += url
        }
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
