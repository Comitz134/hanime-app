package com.nuvio.app.features.updater

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_github_api_error
import org.jetbrains.compose.resources.getString

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

/**
 * The prefix this project's desktop releases carry in front of their version.
 *
 * One repository publishes two clients that number themselves independently, so
 * a bare `v<version>` tag belongs to whichever line reached that number first —
 * desktop 1.0.9 and phone 1.0.9 are the same tag, and the second publish to land
 * there quietly merged itself into the first one's release page. Desktop tags
 * therefore carry `desktop-`, and everything that compares versions has to read
 * the tag through [releaseVersionTag] rather than using it raw: `desktop-v1.0.10`
 * parses as no version at all, so an unstripped tag means an update the window
 * can never see.
 */
internal const val DESKTOP_TAG_PREFIX = "desktop-"

// The namespace proper, as opposed to the `desktop-` some upstream releases use
// as a plain label: a version tag is `desktop-v` and then a number.
private val desktopVersionTagPattern = Regex("^desktop-(?=v\\d)", RegexOption.IGNORE_CASE)

/**
 * A release tag with the desktop namespace removed, when it carries one.
 *
 * Only `desktop-v<version>` is stripped. `desktop-preview` is an ordinary
 * release label with nothing to do with the namespace, and turning it into
 * `preview` would report a release under a name GitHub does not use — and the
 * desktop release list is not the only place such a tag can appear.
 */
internal fun releaseVersionTag(tag: String): String =
    tag.trim().replaceFirst(desktopVersionTagPattern, "").trim()

internal class NoChannelReleaseException : IllegalStateException()

internal data class AppUpdateAssetCandidate(
    val name: String,
    val downloadUrl: String,
    val size: Long? = null,
    val contentType: String? = null,
)

internal fun selectBestUpdateAsset(
    assets: List<AppUpdateAssetCandidate>,
    selector: AppUpdateAssetSelector,
): AppUpdateAssetCandidate? {
    val updateAssets = assets.filter { asset ->
        selector.fileExtensions.any { extension -> asset.name.endsWith(extension, ignoreCase = true) } ||
            selector.contentTypes.any { it.equals(asset.contentType, ignoreCase = true) }
    }
    if (updateAssets.isEmpty()) return null
    if (updateAssets.size == 1) return updateAssets.first()

    for (fragment in selector.preferredNameFragments) {
        updateAssets.firstOrNull { it.name.contains(fragment, ignoreCase = true) }?.let { return it }
    }
    return updateAssets.firstOrNull { asset ->
        selector.fallbackNameFragments.any { asset.name.contains(it, ignoreCase = true) }
    } ?: updateAssets.first()
}

internal object AppUpdaterRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Where a check asks. GitHub, unless this copy was pointed somewhere else —
     * a reader who publishes their own builds points it at their own server with
     * `SEKAI_UPDATE_API`, and the test suite points it at a fixture on loopback,
     * which is what lets the whole check-download-install path be exercised for
     * real instead of against a mocked answer.
     */
    internal fun apiBase(): String =
        System.getProperty("sekai.update.api")?.takeIf { it.isNotBlank() }
            ?: System.getenv("SEKAI_UPDATE_API")?.takeIf { it.isNotBlank() }
            ?: "https://api.github.com"

    suspend fun getLatestChannelUpdate(channel: UpdateChannel): Result<AppUpdate> = runCatching {
        val source = AppUpdaterPlatform.releaseSource
        val response = httpRequestRaw(
            method = "GET",
            url = "${apiBase().trimEnd('/')}/repos/${source.owner}/${source.repo}/${releasePath(channel)}",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to source.userAgent,
            ),
            body = "",
        )
        currentCoroutineContext().ensureActive()
        if (response.status == 404) throw NoChannelReleaseException()
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        selectUpdate(
            responseBody = response.body,
            channel = channel,
            supportedAbis = AppUpdaterPlatform.getSupportedAbis(),
            selector = AppUpdaterPlatform.assetSelector,
        )
            ?: throw NoChannelReleaseException()
    }

    internal fun releasePath(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.STABLE -> "releases/latest"
        UpdateChannel.BETA -> "releases?per_page=100"
        UpdateChannel.ALL_RELEASES -> "releases?per_page=20"
    }

    internal fun selectUpdate(
        responseBody: String,
        channel: UpdateChannel,
        supportedAbis: List<String>,
        selector: AppUpdateAssetSelector = AppUpdateAssetSelector(
            fileExtensions = listOf(".apk"),
            contentTypes = listOf("application/vnd.android.package-archive"),
            preferredNameFragments = supportedAbis,
            fallbackNameFragments = listOf("universal", "all"),
        ),
    ): AppUpdate? {
        val releases = when (channel) {
            UpdateChannel.STABLE -> listOf(json.decodeFromString<GitHubReleaseDto>(responseBody))
            UpdateChannel.BETA, UpdateChannel.ALL_RELEASES ->
                json.decodeFromString<List<GitHubReleaseDto>>(responseBody)
        }
        val eligibleReleases = ReleaseSelector.eligibleReleases(releases, channel)
        // Every eligible release is offered to the asset selection below, not only
        // the newest one. This repository publishes the phone app and the window
        // from the same release list, and the phone's releases carry no asset the
        // window can install: looking only at the newest release would mean a
        // desktop check that reported "nothing new" for as long as a phone release
        // happened to be the most recent thing published — which, with two clients
        // released from one repository, is most of the time. A release with no
        // matching asset is skipped rather than mistaken for an answer, which is
        // exactly what a null from selectBestUpdateAsset means here.
        return eligibleReleases.firstNotNullOfOrNull { release ->
            val asset = selectBestUpdateAsset(
                assets = release.assets.map { candidate ->
                    AppUpdateAssetCandidate(
                        name = candidate.name,
                        downloadUrl = candidate.browserDownloadUrl,
                        size = candidate.size,
                        contentType = candidate.contentType,
                    )
                },
                selector = selector,
            ) ?: return@firstNotNullOfOrNull null
            val tag = if (channel == UpdateChannel.ALL_RELEASES) {
                val raw = release.tagName?.takeIf(String::isNotBlank)
                    ?: release.name?.takeIf(String::isNotBlank)
                    ?: return@firstNotNullOfOrNull null
                releaseVersionTag(raw)
            } else {
                release.tagName
                    ?.let(::releaseVersionTag)
                    ?.takeIf { VersionUtils.parse(it) != null }
                    ?: release.name.orEmpty()
            }
            AppUpdate(
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = release.body.orEmpty(),
                releaseUrl = release.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.downloadUrl,
                assetSizeBytes = asset.size,
            )
        }
    }
}
