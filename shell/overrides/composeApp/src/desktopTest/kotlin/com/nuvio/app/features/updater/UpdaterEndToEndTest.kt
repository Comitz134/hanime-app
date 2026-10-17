package com.nuvio.app.features.updater

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole update, from the app's own code: a check against a real HTTP server
 * that answers like the release API, the asset downloaded over the wire, the
 * archive staged beside the install folder, and the swap performed by the batch
 * file that was generated for it.
 *
 * The pieces are tested separately too, but separately they can all pass while
 * the thing they compose does not: this runs the path an installed app runs,
 * with nothing stubbed except the two things that belong to the machine — the
 * server at the other end, and the launcher that is started at the end of it.
 *
 * It writes to the app's real updates directory (that is where the platform
 * downloads to), which holds nothing but archives of previous downloads, and it
 * points the update source at loopback for its duration.
 */
class UpdaterEndToEndTest {

    private val previousApi = System.getProperty("sekai.update.api")

    @AfterTest
    fun restoreApiBase() {
        if (previousApi == null) System.clearProperty("sekai.update.api")
        else System.setProperty("sekai.update.api", previousApi)
    }

    private fun zipOf(target: File, entries: Map<String, String>): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return target
    }

    @Test
    fun `a check over the release api ends with a replaced install folder`() {
        val work = File(System.getProperty("java.io.tmpdir"), "sekai-e2e-${System.nanoTime()}").apply { mkdirs() }

        // The version this release claims is derived from the one this build
        // carries, so the test says "one patch newer than whatever is running"
        // rather than pinning a number that a version bump would invalidate.
        val current = VersionUtils.parse(AppUpdaterPlatform.currentVersionName)!!
        val next = "${current.major}.${current.minor}.${current.patch + 1}"

        // What the release carries: the app-image, a runtime beside the launcher,
        // and one file that names the version so the swap can be told apart from
        // what was there before.
        val releaseZip = zipOf(File(work, "SEKAI-Windows-x64-$next.zip"), mapOf(
            "SEKAI/SEKAI.exe" to "new launcher",
            "SEKAI/app/composeApp-desktop-new.jar" to "new jar",
            "SEKAI/node/node.exe" to "node runtime",
            "SEKAI/version.txt" to next,
        ))
        val releaseBytes = releaseZip.readBytes()
        val assetName = releaseZip.name

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/repos/Comitz134/hanime-app/releases") { exchange ->
            val body = """
                [
                  {"tag_name":"v9.9.9","name":"Phone release","body":"","draft":false,
                   "prerelease":false,"html_url":"http://127.0.0.1:$port/releases/tag/v9.9.9",
                   "assets":[{"name":"sekai-9.9.9.apk",
                              "browser_download_url":"http://127.0.0.1:$port/dl/sekai-9.9.9.apk",
                              "size":10,"content_type":"application/vnd.android.package-archive"}]},
                  {"tag_name":"v$next","name":"SEKAI $next","body":"fixture notes","draft":false,
                   "prerelease":true,"html_url":"http://127.0.0.1:$port/releases/tag/v$next",
                   "assets":[{"name":"$assetName",
                              "browser_download_url":"http://127.0.0.1:$port/dl/$assetName",
                              "size":${releaseBytes.size},"content_type":"application/zip"}]}
                ]
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/dl/") { exchange ->
            exchange.sendResponseHeaders(200, releaseBytes.size.toLong())
            exchange.responseBody.use { it.write(releaseBytes) }
        }
        server.start()

        try {
            System.setProperty("sekai.update.api", "http://127.0.0.1:$port")

            // 1. The check, over the wire, against the same code the app runs.
            val update = runBlocking {
                AppUpdaterRepository.getLatestChannelUpdate(UpdateChannel.ALL_RELEASES)
            }.getOrThrow()

            assertEquals("v$next", update.tag, "the phone's release must not be mistaken for ours")
            assertEquals(assetName, update.assetName)
            assertTrue(VersionUtils.isRemoteNewerLegacy(update.tag, AppUpdaterPlatform.currentVersionName))

            // 2. The download, over the wire, to where the app keeps them.
            val downloaded = runBlocking {
                AppUpdaterPlatform.downloadUpdateAsset(update.assetUrl, update.assetName) { _, _ -> }
            }.getOrThrow()
            assertTrue(File(downloaded).isFile, "the asset must be on disk after the download")
            assertEquals(releaseBytes.size.toLong(), File(downloaded).length())

            // 3. Staging, against a folder that looks like an install.
            val installDir = File(work, "install/SEKAI")
            File(installDir, "SEKAI.exe").apply { parentFile.mkdirs() }.writeText("old launcher")
            File(installDir, "app/composeApp-desktop-old.jar").apply { parentFile.mkdirs() }.writeText("old jar")

            val staged = stagePortableUpdate(File(downloaded), installDir)
            assertTrue(staged.script.isFile, "the update must be staged where it can be run from")

            // 4. The swap, run for real. The pid is one that has certainly ended,
            //    so the script's wait is the "the app is gone" path.
            val dead = ProcessBuilder("cmd.exe", "/c", "exit", "0").start()
            assertTrue(dead.waitFor(30, TimeUnit.SECONDS))
            val marker = File(work, "relaunched.txt")
            val launcher = File(work, "launcher.cmd").apply {
                writeText("@echo off\r\necho yes > \"${marker.absolutePath}\"\r\n")
            }
            // Rewritten with this test's own pid (the staged one waits for the
            // test JVM, which is still very much alive) and its own launcher, so
            // the swap is performed by the same script shape, aimed here.
            val swap = File(work, "apply.cmd").apply {
                writeText(
                    windowsPortableReplaceScript(
                        stagedDir = staged.stagedDir.absolutePath,
                        installDir = installDir.absolutePath,
                        launcherPath = launcher.absolutePath,
                        currentPid = dead.pid(),
                        updateArchive = downloaded,
                    ),
                    charset = Charsets.US_ASCII,
                )
            }
            val run = ProcessBuilder("cmd.exe", "/c", swap.absolutePath).start()
            assertTrue(run.waitFor(120, TimeUnit.SECONDS), "the swap did not finish")
            assertEquals(0, run.exitValue())

            assertEquals("new launcher", File(installDir, "SEKAI.exe").readText())
            assertEquals("new jar", File(installDir, "app/composeApp-desktop-new.jar").readText())
            assertEquals("node runtime", File(installDir, "node/node.exe").readText())
            assertEquals(next, File(installDir, "version.txt").readText())
            assertFalse(File(installDir, "app/composeApp-desktop-old.jar").exists())

            // A bounded wait rather than an early return: the rest of this test
            // is about what is on disk *after* the swap, and it has to run.
            val deadline = System.currentTimeMillis() + 10_000
            while (!marker.exists() && System.currentTimeMillis() < deadline) Thread.sleep(250)
            assertTrue(marker.exists(), "the updated app was never started")

            // 5. And nothing the update was made of is left in the app's own
            //    updates folder — neither the archive it downloaded from that
            //    server nor the tree staged out of it. Each is the size of the
            //    app: an update that keeps them grows app data by ~600 MB a
            //    release, and the folder belongs to the reader.
            assertFalse(File(downloaded).exists(), "the download was left behind")
            assertFalse(staged.stagedDir.exists(), "the staged tree was left behind")
            assertFalse(
                File(downloaded).parentFile.listFiles().orEmpty().any { it.name.startsWith("staged-") },
                "a staging folder was left behind",
            )
        } finally {
            server.stop(0)
            work.deleteRecursively()
        }
    }
}
