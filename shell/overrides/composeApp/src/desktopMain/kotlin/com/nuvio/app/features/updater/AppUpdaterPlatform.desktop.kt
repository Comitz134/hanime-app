package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_download_failed
import nuvio.composeapp.generated.resources.updates_download_failed_http
import nuvio.composeapp.generated.resources.updates_downloaded_file_missing
import nuvio.composeapp.generated.resources.updates_empty_download_body
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

// How long the batch file gives the window to exit before it applies the update
// anyway. It is also the floor on what the app treats as abandoned staging:
// nothing a script could still be using is anywhere near this old.
internal const val updateApplyWaitSeconds = 600
private const val updateLeftoverGraceSeconds = 300

private const val desktopUpdaterPreferencesName = "nuvio_updater"
private const val ignoredTagKey = "ignored_release_tag"
private const val updateChannelKey = "update_channel"

private val desktopUpdaterHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

actual object AppUpdaterPlatform {
    private val currentOs: DesktopUpdaterOs = DesktopUpdaterOs.current()
    private val store = DesktopStorage.store(desktopUpdaterPreferencesName)
    actual val isDebugBuild: Boolean = false
    actual val hasSingleUpdateChannel: Boolean = true

    // Linux ships four package formats and the running app is the only place
    // that can tell which one it was installed from, so the format is resolved
    // once and then drives both the asset choice and the install command.
    private val linuxInstallMethod: LinuxInstallMethod by lazy {
        if (currentOs == DesktopUpdaterOs.LINUX) detectLinuxInstallMethod() else LinuxInstallMethod.UNKNOWN
    }

    // A Flatpak cannot install anything for itself: the sandbox has no write
    // access to /app and the manifest grants no talk-name for the host Flatpak
    // service, so the update belongs to the user's store, not to this dialog.
    actual val isSupported: Boolean
        get() = currentOs != DesktopUpdaterOs.UNKNOWN && linuxInstallMethod != LinuxInstallMethod.FLATPAK

    // This fork's own releases, not upstream Nuvio's. The window is built from
    // NuvioDesktop, but it is not NuvioDesktop any more: its installer name, its
    // app-data directory and its version number are all its own, so an update
    // that pointed at NuvioMedia/NuvioDesktop would offer to replace this app
    // with a different product.
    //
    // `includePrereleases` is not a preference here, it is a requirement: the
    // same repository publishes the phone app, and the phone reads its manifest
    // from `releases/latest`. Every desktop release is therefore published as a
    // prerelease so that `/releases/latest` keeps resolving to the phone's
    // release, and a check that skipped prereleases would never see a desktop
    // update at all.
    actual val releaseSource: AppUpdateReleaseSource = AppUpdateReleaseSource(
        owner = "Comitz134",
        repo = "hanime-app",
        channelBranch = null,
        includePrereleases = true,
        userAgent = "SEKAI",
    )

    actual val assetSelector: AppUpdateAssetSelector
        get() = currentOs.assetSelector(linuxInstallMethod)

    actual val currentVersionName: String = AppVersionConfig.DESKTOP_VERSION_NAME

    actual fun getSupportedAbis(): List<String> = emptyList()

    actual fun getIgnoredTag(): String? = store.getString(ignoredTagKey)

    actual fun setIgnoredTag(tag: String?) {
        store.putString(ignoredTagKey, tag)
    }

    actual fun getUpdateChannel(): String? = store.getString(updateChannelKey)

    actual fun setUpdateChannel(channel: String) {
        store.putString(updateChannelKey, channel)
    }

    actual fun deleteDownloadedUpdate(path: String) {
        File(path).delete()
    }

    actual suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            clearDir(updatesDir())
            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destination = File(updatesDir(), safeName)
            val tempFile = File(updatesDir(), "$safeName.part")

            val request = HttpRequest.newBuilder()
                .uri(URI(assetUrl))
                .GET()
                .build()
            val response = desktopUpdaterHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                error(runBlocking { getString(Res.string.updates_download_failed_http, response.statusCode()) })
            }

            val totalBytes = response.headers().firstValue("Content-Length").orElse(null)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            var downloadedBytes = 0L
            try {
                response.body()?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read.toLong()
                            onProgress(downloadedBytes, totalBytes)
                        }
                        output.flush()
                    }
                } ?: error(runBlocking { getString(Res.string.updates_empty_download_body) })

                if (totalBytes != null && downloadedBytes != totalBytes) {
                    error(runBlocking { getString(Res.string.updates_download_failed) })
                }

                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }
                destination.absolutePath
            } catch (t: Throwable) {
                if (tempFile.exists()) tempFile.delete()
                throw t
            }
        }
    }

    actual fun canInstallDownloadedUpdate(): Boolean = true

    actual fun openInstallPermissionSettings() = Unit

    actual fun installDownloadedUpdate(path: String): Result<Unit> = runCatching {
        val updateFile = File(path)
        check(updateFile.exists()) { runBlocking { getString(Res.string.updates_downloaded_file_missing) } }

        launchInstaller(updateFile)
        scheduleAppExit()
    }

    private fun updatesDir(): File = desktopUpdatesDir()

    private fun clearDir(dirPath: File) {
        if (dirPath.exists() and dirPath.isDirectory) {
            dirPath.listFiles()?.forEach { file ->
                file.deleteRecursively()
            }
        }
    }

    private fun launchInstaller(updateFile: File) {
        when (currentOs) {
            // Windows gets the portable tree this project actually publishes. An
            // installer cannot be used here — the desktop build needs no WiX to
            // be produced, so what a release carries is a zip of the jpackage
            // app-image, and updating means replacing that folder.
            DesktopUpdaterOs.WINDOWS ->
                if (updateFile.extension.equals("zip", ignoreCase = true)) {
                    installPortableZip(updateFile)
                } else {
                    ProcessBuilder(windowsInstallerCommand(updateFile)).start()
                }
            DesktopUpdaterOs.MACOS -> ProcessBuilder(listOf("open", updateFile.absolutePath)).start()
            DesktopUpdaterOs.LINUX -> ProcessBuilder(
                linuxInstallerCommand(
                    method = linuxInstallMethod,
                    updateFile = updateFile,
                    appImagePath = System.getenv(appImageEnvName),
                    currentPid = ProcessHandle.current().pid(),
                )
            ).start()
            DesktopUpdaterOs.UNKNOWN -> error("Desktop updates are not supported on this operating system.")
        }
    }

    private fun scheduleAppExit() {
        thread(name = "nuvio-updater-exit", isDaemon = true) {
            Thread.sleep(500)
            exitProcess(0)
        }
    }

    /**
     * Replace the installed app-image with the tree inside a downloaded zip.
     *
     * The running app cannot replace its own files: the launcher and the jars
     * under it are locked for as long as this process lives. So the work is
     * split in two — everything that can fail happens *here*, while there is
     * still a working app to report it, and only then is a small batch file
     * started, which waits for this process to end, mirrors the staged tree
     * over the install folder and starts the new launcher.
     */
    private fun installPortableZip(updateFile: File) {
        val installDir = resolveInstalledAppDir(
            jpackageAppPath = System.getProperty(jpackageAppPathProperty),
            launcherExists = { path -> File(path).isFile },
        ) ?: error(
            "This copy of SEKAI was not started from its installed folder, so it cannot update itself. " +
                "Download the new version by hand instead."
        )

        val staged = stagePortableUpdate(updateFile, installDir)

        // Detached, and minimised: it has to outlive this process, and the only
        // thing it shows is a window nobody asked for. `start` is what returns
        // immediately instead of holding this process open until it finishes.
        ProcessBuilder(
            "cmd.exe", "/c", "start", "SEKAI update", "/min",
            "\"${staged.script.absolutePath}\"",
        ).start()
    }
}

/** A staged update: the tree that will replace the install folder, and the batch file that does it. */
internal data class StagedUpdate(val stagedDir: File, val script: File)

/**
 * Unpack a downloaded release beside the install folder and write the batch file
 * that will put it in place once this process is gone.
 *
 * Nothing touches [installDir] here: the extraction and the launcher search both
 * have to succeed first, and both can fail on a truncated download or an archive
 * that is not an app at all. An update that cannot be staged has to leave the
 * installed app exactly as it was — which is the whole reason this is separate
 * from the step that runs it.
 */
internal fun stagePortableUpdate(updateFile: File, installDir: File): StagedUpdate {
    val stagingRoot = File(updateFile.parentFile, "staged-${updateFile.nameWithoutExtension}")
    stagingRoot.deleteRecursively()
    stagingRoot.mkdirs()

    val extracted = extractZip(updateFile, stagingRoot)
    val launcher = findBundledLauncher(extracted)
        ?: error("The downloaded archive does not contain the app (no launcher inside it).")
    // The tree that replaces the install folder: the zip's own wrapper
    // directory, whatever it is called, so a repacked release works too.
    val stagedDir = launcher.parentFile

    val script = File(updateFile.parentFile, "apply-update.cmd")
    script.writeText(
        windowsPortableReplaceScript(
            stagedDir = stagedDir.absolutePath,
            installDir = installDir.absolutePath,
            launcherPath = launcher.absolutePath,
            currentPid = ProcessHandle.current().pid(),
            updateArchive = updateFile.absolutePath,
        ),
        charset = Charsets.US_ASCII,
    )
    return StagedUpdate(stagedDir = stagedDir, script = script)
}

/**
 * Where the running app lives, from the launcher jpackage recorded.
 *
 * A launcher path that no longer exists is worse than none: mirroring an update
 * into a folder that is not there would create it and install the app a second
 * time, which is why the existence check is part of the resolution rather than
 * of the caller.
 */
/** Where an update is downloaded to and staged in: the app's own app-data folder. */
internal fun desktopUpdatesDir(): File =
    File(DesktopStorage.rootDir.resolve("updates").also { it.createDirectories() }.toUri())

/**
 * Reclaim what an update no longer needs, and leave alone what one still might.
 *
 * An update puts two full copies of the app on disk — the archive it downloaded
 * and the tree staged out of it — beside the user's app data, and both exist to
 * be used once, by the batch file that swaps them in. That script removes its
 * own copies when the swap succeeds; this is for every other ending: a swap that
 * failed (the script keeps its tree on purpose), a download abandoned before it
 * was ever installed, a `.part` file from a process killed mid-download, and the
 * copies left by releases that cleaned up nothing at all — routinely ~600 MB in
 * `%APPDATA%\Hanime\updates`, which is the leak this is here to stop.
 *
 * The age bound is the batch file's own wait ([updateApplyWaitSeconds]) plus a
 * margin: it holds an update for at most that long while the window exits, so
 * anything older cannot belong to a swap still in progress, and a live download
 * is younger by definition and is left alone.
 *
 * @return the entries it deleted, for a caller that wants to report them.
 */
internal fun pruneStaleUpdateLeftovers(
    updatesDir: File = desktopUpdatesDir(),
    now: Long = System.currentTimeMillis(),
    staleAfterMillis: Long = (updateApplyWaitSeconds + updateLeftoverGraceSeconds) * 1000L,
): List<File> {
    if (!updatesDir.isDirectory) return emptyList()
    val cutoff = now - staleAfterMillis
    return updatesDir.listFiles().orEmpty()
        .filter { entry -> newestModifiedAt(entry) < cutoff }
        .filter { entry -> runCatching { entry.deleteRecursively() }.getOrDefault(false) }
}

/**
 * The most recent modification anywhere under [file]. A staged tree is written
 * from the inside out, so the folder's own timestamp says the least about it —
 * the wrapper is created first and never touched again.
 */
private fun newestModifiedAt(file: File): Long =
    runCatching { file.walkTopDown().maxOf { it.lastModified() } }.getOrDefault(file.lastModified())

internal fun resolveInstalledAppDir(
    jpackageAppPath: String?,
    launcherExists: (String) -> Boolean,
): File? {
    val launcher = jpackageAppPath?.takeIf { it.isNotBlank() } ?: return null
    if (!launcherExists(launcher)) return null
    return File(launcher).parentFile
}

/**
 * Unpack a zip into [targetDir], refusing anything that would land outside it.
 *
 * An archive's entry names are attacker-controlled strings: `..\\..\\Windows\\`
 * in a name is the classic way a "portable app" writes wherever it likes on the
 * machine of whoever unpacks it. Every entry is therefore resolved against the
 * target and checked to still be inside it.
 */
internal fun extractZip(zipFile: File, targetDir: File): File {
    val root = targetDir.canonicalFile
    root.mkdirs()
    ZipFile(zipFile).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val out = File(root, entry.name)
            val canonical = out.canonicalFile
            val inside = canonical == root ||
                canonical.path.startsWith(root.path + File.separator)
            require(inside) { "the archive contains an entry outside itself: ${entry.name}" }

            if (entry.isDirectory) {
                canonical.mkdirs()
                continue
            }
            canonical.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                FileOutputStream(canonical).use { output -> input.copyTo(output) }
            }
        }
    }
    return root
}

/**
 * The launcher inside an extracted tree — the app-image root that should
 * replace the install folder. Null when the archive is not an app-image.
 */
internal fun findBundledLauncher(extractedRoot: File): File? = launcherNames()
    .asSequence()
    .map { name -> File(extractedRoot, name) }
    .firstOrNull { it.isFile }
    ?: extractedRoot.listFiles()
        ?.filter(File::isDirectory)
        ?.asSequence()
        ?.flatMap { dir -> launcherNames().asSequence().map { name -> File(dir, name) } }
        ?.firstOrNull { it.isFile }

/** The launcher name jpackage produces, per host. */
internal fun launcherNames(): List<String> {
    val exe = if (DesktopUpdaterOs.current() == DesktopUpdaterOs.WINDOWS) "SEKAI.exe" else "SEKAI"
    return listOf(exe)
}

/**
 * The batch file that finishes an update after this process is gone.
 *
 * It waits for [currentPid] rather than sleeping a fixed time: the app exits
 * from a different thread than the one that started this, and the wait has to
 * outlast a shutdown that is itself waiting on the bundled Node server. A
 * bounded loop means a process that somehow never exits costs a delay, not an
 * update that never happens.
 *
 * `/MIR` is deliberate: the install folder is code the release produced, and
 * nothing else writes there — the window's own state (its profile, its addons,
 * the bundled server it materialises) lives under the user's app-data
 * directory. Anything the new release no longer produces has to go, or the old
 * jars would keep loading beside the new ones.
 */internal fun windowsPortableReplaceScript(
    stagedDir: String,
    installDir: String,
    launcherPath: String,
    currentPid: Long,
    updateArchive: String,
): String {

    val lines = listOf(
        "@echo off",
        "setlocal",
        "set \"STAGED=$stagedDir\"",
        "set \"TARGET=$installDir\"",
        "set \"LAUNCH=$launcherPath\"",
        "set \"ARCHIVE=$updateArchive\"",
        // The updates folder, taken from the archive rather than passed in:
        // the archive is the one path this script is always handed, and `%~dp`
        // is the drive and folder of it, trailing separator included.
        "for %%F in (\"%ARCHIVE%\") do set \"UPDATES=%%~dpF\"",
        "set /a WAITED=0",
        ":wait",
        "tasklist /FI \"PID eq $currentPid\" /NH 2>nul | find \"$currentPid\" >nul",
        "if errorlevel 1 goto apply",
        "set /a WAITED+=1",
        "if %WAITED% geq $updateApplyWaitSeconds goto apply",
        "timeout /t 1 /nobreak >nul",
        "goto wait",
        ":apply",
        "timeout /t 1 /nobreak >nul",
        "robocopy \"%STAGED%\" \"%TARGET%\" /MIR /NFL /NDL /NJH /NJS /NP /R:2 /W:1",
        "if errorlevel 8 goto failed",
        "start \"\" \"%LAUNCH%\"",
        "call :prune",
        "exit /b 0",
        ":failed",
        // A failed swap keeps everything it used: that tree is the only
        // evidence of what went wrong, and the running app prunes staging it
        // finds abandoned the next time it starts.
        "start \"\" \"%LAUNCH%\"",
        "exit /b 1",
        // An update writes two full copies of the app into the user's app-data
        // folder — the archive it downloaded and the tree staged out of it —
        // and this swap is the only thing either of them is for. Left behind
        // they are ~600 MB that no later update ever reclaims: a download
        // clears the folder it is about to use, and then fills it again.
        // The sweep is by name so it also collects staging an interrupted
        // update left there, which is the same leak, only older.
        ":prune",
        "rmdir /s /q \"%STAGED%\" 2>nul",
        "del /f /q \"%ARCHIVE%\" 2>nul",
        "for /d %%D in (\"%UPDATES%staged-*\") do rmdir /s /q \"%%~D\" 2>nul",
        "exit /b 0",
        "",
    )
    return lines.joinToString(separator = "\r\n")
}

private enum class DesktopUpdaterOs {
    WINDOWS,
    MACOS,
    LINUX,
    UNKNOWN;

    fun assetSelector(linuxInstallMethod: LinuxInstallMethod): AppUpdateAssetSelector {
        val archFragments = desktopArchitectureFragments()
        return when (this) {
            // What a release carries for Windows is the portable tree — a zip of
            // the app-image, which is also what a reader downloads by hand from
            // the release page. There is no MSI in this fork's releases: the
            // desktop build produces an app-image, and turning that into an
            // installer would mean a WiX toolset on the machine that builds it.
            WINDOWS -> AppUpdateAssetSelector(
                fileExtensions = listOf(".zip"),
                contentTypes = listOf("application/zip"),
                preferredNameFragments = archFragments + listOf("windows", "win"),
                fallbackNameFragments = listOf("portable", "universal", "all"),
            )
            MACOS -> AppUpdateAssetSelector(
                fileExtensions = listOf(".dmg", ".pkg"),
                preferredNameFragments = archFragments + listOf("macos", "mac", "darwin"),
                fallbackNameFragments = listOf("universal", "all"),
            )
            LINUX -> AppUpdateAssetSelector(
                fileExtensions = linuxUpdateFileExtensions(linuxInstallMethod),
                preferredNameFragments = archFragments + listOf("linux"),
                fallbackNameFragments = listOf("universal", "all"),
            )
            UNKNOWN -> AppUpdateAssetSelector(fileExtensions = emptyList())
        }
    }

    companion object {
        fun current(): DesktopUpdaterOs {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            return when {
                osName.contains("win") -> WINDOWS
                osName.contains("mac") -> MACOS
                osName.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }
    }
}

private fun desktopArchitectureFragments(): List<String> {
    val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
    return when {
        arch == "aarch64" || arch == "arm64" -> listOf("arm64", "aarch64")
        arch == "x86" || arch == "i386" || arch == "i686" -> listOf("x86", "i386", "i686")
        arch.contains("64") -> listOf("x64", "x86_64", "amd64")
        else -> emptyList()
    }
}

internal fun windowsInstallerCommand(updateFile: File): List<String> {
    if (!updateFile.extension.equals("msi", ignoreCase = true)) {
        return listOf(updateFile.absolutePath)
    }

    return listOf("msiexec", "/i", updateFile.absolutePath)
}

internal enum class LinuxInstallMethod {
    APP_IMAGE,
    FLATPAK,
    RPM,
    DEB,
    UNKNOWN,
}

internal const val appImageEnvName = "APPIMAGE"
private const val flatpakIdEnvName = "FLATPAK_ID"
private const val flatpakInfoPath = "/.flatpak-info"
private const val jpackageAppPathProperty = "jpackage.app-path"
private const val javaExecutableName = "java"
private const val packageQueryTimeoutSeconds = 3L
private const val appImageExitWaitTicks = 100

// A release carries every Linux format at once, so the extension list has to be
// the one the running install can actually consume. An unresolved install keeps
// the previous list, leaving source and tarball builds no worse off than before.
internal fun linuxUpdateFileExtensions(method: LinuxInstallMethod): List<String> = when (method) {
    LinuxInstallMethod.APP_IMAGE -> listOf(".AppImage")
    LinuxInstallMethod.RPM -> listOf(".rpm")
    LinuxInstallMethod.DEB -> listOf(".deb")
    LinuxInstallMethod.FLATPAK -> emptyList()
    LinuxInstallMethod.UNKNOWN -> listOf(".deb", ".AppImage")
}

internal fun resolveLinuxInstallMethod(
    appImagePath: String?,
    appImageExists: Boolean,
    flatpakId: String?,
    flatpakInfoExists: Boolean,
    launcherPath: String?,
    isOwnedByRpm: (String) -> Boolean,
    isOwnedByDpkg: (String) -> Boolean,
): LinuxInstallMethod {
    if (!appImagePath.isNullOrBlank() && appImageExists) return LinuxInstallMethod.APP_IMAGE
    if (!flatpakId.isNullOrBlank() || flatpakInfoExists) return LinuxInstallMethod.FLATPAK

    val path = launcherPath?.takeIf { it.isNotBlank() } ?: return LinuxInstallMethod.UNKNOWN
    if (isOwnedByRpm(path)) return LinuxInstallMethod.RPM
    if (isOwnedByDpkg(path)) return LinuxInstallMethod.DEB
    return LinuxInstallMethod.UNKNOWN
}

private fun detectLinuxInstallMethod(): LinuxInstallMethod {
    val appImagePath = System.getenv(appImageEnvName)
    return resolveLinuxInstallMethod(
        appImagePath = appImagePath,
        appImageExists = !appImagePath.isNullOrBlank() && File(appImagePath).exists(),
        flatpakId = System.getenv(flatpakIdEnvName),
        flatpakInfoExists = File(flatpakInfoPath).exists(),
        launcherPath = linuxLauncherPath(),
        isOwnedByRpm = { path -> packageQuerySucceeds(listOf("rpm", "-qf", path)) },
        isOwnedByDpkg = { path -> packageQuerySucceeds(listOf("dpkg", "-S", path)) },
    )
}

private fun linuxLauncherPath(): String? = linuxLauncherPathFrom(
    jpackageAppPath = System.getProperty(jpackageAppPathProperty),
    processCommand = ProcessHandle.current().info().command().orElse(null),
)

// jpackage records the launcher it started, which is the only path that belongs
// to this app's package. Without it the process command is the JVM itself, and
// the JVM belongs to the distribution's own java package -- asking rpm or dpkg
// who owns that would report a packaged install that is not Nuvio, so a source
// or tarball run is left unresolved instead.
internal fun linuxLauncherPathFrom(
    jpackageAppPath: String?,
    processCommand: String?,
): String? {
    jpackageAppPath?.takeIf { it.isNotBlank() }?.let { return it }

    return processCommand?.takeIf { it.isNotBlank() && File(it).name != javaExecutableName }
}

private fun packageQuerySucceeds(command: List<String>): Boolean = runCatching {
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(packageQueryTimeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching false
    }
    process.exitValue() == 0
}.getOrDefault(false)

internal fun linuxInstallerCommand(
    method: LinuxInstallMethod,
    updateFile: File,
    appImagePath: String?,
    currentPid: Long,
): List<String> {
    if (method == LinuxInstallMethod.APP_IMAGE && !appImagePath.isNullOrBlank()) {
        return listOf("sh", "-c", appImageReplaceScript(updateFile.absolutePath, appImagePath, currentPid))
    }

    return listOf("xdg-open", updateFile.absolutePath)
}

// An AppImage has no installer: updating one means replacing the file that is
// running. The copy waits for this process to exit first, both because the
// running image is still mounted from that file and because a half-written one
// would leave nothing to start. When the image sits somewhere this user cannot
// write, the download is handed to the desktop rather than failing in silence.
internal fun appImageReplaceScript(
    downloadedPath: String,
    appImagePath: String,
    currentPid: Long,
): String {
    val downloaded = singleQuote(downloadedPath)
    val target = singleQuote(appImagePath)
    return buildString {
        append("i=0; ")
        append("while [ \$i -lt $appImageExitWaitTicks ] && kill -0 $currentPid 2>/dev/null; do ")
        append("sleep 0.1; i=\$((i+1)); ")
        append("done; ")
        append("if cp -f -- $downloaded $target; then ")
        append("chmod +x -- $target; rm -f -- $downloaded; exec $target; ")
        append("else xdg-open $downloaded; fi")
    }
}

private fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
