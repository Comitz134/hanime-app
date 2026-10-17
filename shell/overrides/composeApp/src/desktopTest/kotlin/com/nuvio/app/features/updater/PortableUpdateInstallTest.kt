package com.nuvio.app.features.updater

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Windows half of the updater: what a downloaded release is unpacked into,
 * which folder it replaces, and the batch file that does the replacing once this
 * process is gone.
 *
 * The batch file is not merely inspected — it is written to disk and run, against
 * a folder that looks like an install, because that is the only way to know that
 * the thing a reader's machine will execute actually finishes the job: a script
 * can read correctly and still fail on quoting, on `robocopy`'s exit codes, or on
 * waiting for the wrong process.
 */
class PortableUpdateInstallTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "sekai-update-test-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
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

    private fun write(file: File, body: String) {
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    // ------------------------------------------------------------------ unzip

    @Test
    fun `an archive is unpacked under the folder it was given`() {
        val dir = tempDir("unzip")
        val zip = zipOf(File(dir, "update.zip"), mapOf(
            "SEKAI/SEKAI.exe" to "launcher",
            "SEKAI/app/sekai.jar" to "jar",
            "SEKAI/server/src/server.mjs" to "server",
        ))

        val root = extractZip(zip, File(dir, "staged"))

        assertEquals("launcher", File(root, "SEKAI/SEKAI.exe").readText())
        assertEquals("jar", File(root, "SEKAI/app/sekai.jar").readText())
        assertEquals("server", File(root, "SEKAI/server/src/server.mjs").readText())
    }

    @Test
    fun `an entry that climbs out of the archive is refused`() {
        val dir = tempDir("zip-slip")
        val zip = zipOf(File(dir, "evil.zip"), mapOf(
            "../outside.txt" to "escaped",
            "SEKAI/SEKAI.exe" to "launcher",
        ))

        val failure = assertFailsWith<IllegalArgumentException> {
            extractZip(zip, File(dir, "staged"))
        }
        assertTrue(failure.message.orEmpty().contains("../outside.txt"))
        // And nothing was written outside: the guard is at the entry, not after.
        assertFalse(File(dir, "outside.txt").exists())
    }

    @Test
    fun `the launcher is found inside the archive's own wrapper folder`() {
        val dir = tempDir("launcher-nested")
        write(File(dir, "SEKAI/SEKAI.exe"), "launcher")
        write(File(dir, "SEKAI/app/sekai.jar"), "jar")

        assertEquals(File(dir, "SEKAI/SEKAI.exe"), findBundledLauncher(dir))
    }

    @Test
    fun `the launcher is found at the root of an archive without a wrapper`() {
        val dir = tempDir("launcher-flat")
        write(File(dir, "SEKAI.exe"), "launcher")

        assertEquals(File(dir, "SEKAI.exe"), findBundledLauncher(dir))
    }

    @Test
    fun `an archive with no launcher in it has none to find`() {
        val dir = tempDir("launcher-missing")
        write(File(dir, "SEKAI/readme.txt"), "not an app")

        assertNull(findBundledLauncher(dir))
    }

    // ---------------------------------------------------------- install folder

    @Test
    fun `the install folder is the launcher's own, and only when it is there`() {
        val dir = tempDir("installdir")
        val launcher = File(dir, "SEKAI/SEKAI.exe")
        write(launcher, "launcher")

        assertEquals(
            File(dir, "SEKAI"),
            resolveInstalledAppDir(launcher.absolutePath) { true },
        )
        // A folder that is not where the running app lives must not be invented:
        // mirroring into it would install the app a second time.
        assertNull(resolveInstalledAppDir(launcher.absolutePath) { false })
        assertNull(resolveInstalledAppDir(null) { true })
        assertNull(resolveInstalledAppDir("  ") { true })
    }

    // ------------------------------------------------------- the batch file

    @Test
    fun `the batch file waits for this process, mirrors the tree and starts the new app`() {
        val script = windowsPortableReplaceScript(
            stagedDir = "C:\\staged\\SEKAI",
            installDir = "C:\\install\\SEKAI",
            launcherPath = "C:\\install\\SEKAI\\SEKAI.exe",
            currentPid = 4321,
            updateArchive = "C:\\updates\\SEKAI-Windows-x64-1.0.2.zip",
        )

        // CRLF, or cmd.exe reads a line's tail as part of the next command.
        assertTrue(script.startsWith("@echo off\r\n"), "script must be a batch file")
        assertTrue(script.contains("tasklist /FI \"PID eq 4321\""))
        assertTrue(script.contains("robocopy \"%STAGED%\" \"%TARGET%\" /MIR"))
        assertTrue(script.contains("if errorlevel 8 goto failed"))
        assertTrue(script.contains("start \"\" \"%LAUNCH%\""))
        assertTrue(script.contains("C:\\install\\SEKAI\\SEKAI.exe"))

        // The two copies the update needed are removed once they have been
        // used, and only on the success path: a failed robocopy keeps them.
        assertTrue(script.contains("call :prune"), "the swap leaves its own copies behind")
        assertTrue(
            script.indexOf("if errorlevel 8 goto failed") < script.indexOf("call :prune"),
            "a failed copy must reach its failure path before anything is pruned",
        )
        assertTrue(script.indexOf(":apply") < script.indexOf(":prune"))
    }

    @Test
    fun `running the batch file replaces a folder while keeping nothing stale`() {
        // A pid that has certainly ended, so the wait is the "process is gone"
        // path rather than the bounded-loop path.
        val dead = ProcessBuilder("cmd.exe", "/c", "exit", "0").start()
        assertTrue(dead.waitFor(30, TimeUnit.SECONDS))
        val deadPid = dead.pid()

        val dir = tempDir("swap")
        val install = File(dir, "install/SEKAI")
        // Laid out the way the app lays an update out: the archive in the
        // app-data updates folder, the tree beside it under the name the app
        // gives it (`staged-<archive name>`), plus a second staging folder an
        // earlier, interrupted update left there.
        val updates = File(dir, "updates")
        val archive = File(updates, "SEKAI-Windows-x64-1.0.2.zip").apply {
            parentFile.mkdirs()
            writeText("the archive the update was staged from")
        }
        val olderStaging = File(updates, "staged-SEKAI-Windows-x64-1.0.1")
        write(File(olderStaging, "SEKAI/SEKAI.exe"), "an earlier update's copy")
        val staged = File(updates, "staged-SEKAI-Windows-x64-1.0.2/SEKAI")

        // What an installed build looks like, including a jar the new release no
        // longer produces — the /MIR half of the swap has to delete it.
        write(File(install, "SEKAI.exe"), "old launcher")
        write(File(install, "app/composeApp-desktop-old.jar"), "old jar")
        write(File(install, "app/.jpackage.xml"), "<app-version>1.0.0</app-version>")

        write(File(staged, "SEKAI.exe"), "new launcher")
        write(File(staged, "app/composeApp-desktop-new.jar"), "new jar")
        write(File(staged, "node/node.exe"), "node")
        write(File(staged, "server/src/server.mjs"), "server")

        // The relaunch step, stood in for: a batch file that records that it was
        // started and exits. `start` returns immediately, so the marker is what
        // proves the new build is the thing that was launched.
        val marker = File(dir, "relaunched.txt")
        val launcher = File(dir, "fake-launcher.cmd")
        launcher.writeText("@echo off\r\necho launched > \"${marker.absolutePath}\"\r\n")

        val script = File(dir, "apply-update.cmd")
        script.writeText(
            windowsPortableReplaceScript(
                stagedDir = staged.absolutePath,
                installDir = install.absolutePath,
                launcherPath = launcher.absolutePath,
                currentPid = deadPid,
                updateArchive = archive.absolutePath,
            ),
            charset = Charsets.US_ASCII,
        )

        val run = ProcessBuilder("cmd.exe", "/c", script.absolutePath).start()
        assertTrue(run.waitFor(120, TimeUnit.SECONDS), "the apply script did not finish")
        assertEquals(0, run.exitValue(), "the apply script reported a failed copy")

        assertEquals("new launcher", File(install, "SEKAI.exe").readText())
        assertEquals("new jar", File(install, "app/composeApp-desktop-new.jar").readText())
        assertEquals("node", File(install, "node/node.exe").readText())
        assertEquals("server", File(install, "server/src/server.mjs").readText())
        assertFalse(
            File(install, "app/composeApp-desktop-old.jar").exists(),
            "a jar the new release does not produce must not survive the update",
        )

        // The relaunch is asynchronous; give the new process a moment to write.
        repeat(40) {
            if (marker.exists()) return
            Thread.sleep(250)
        }
        assertTrue(marker.exists(), "the updated app was never started")

        // And the copies the update was made of are gone: the archive it
        // downloaded, the tree it staged, and the staging an earlier update
        // abandoned in the same folder. Left there they are ~600 MB that
        // nothing else ever reclaims — a later download clears the folder it
        // is about to use, and then fills it again.
        assertFalse(archive.exists(), "the downloaded archive was left behind")
        assertFalse(staged.exists(), "the staged tree was left behind")
        assertFalse(
            File(updates, "staged-SEKAI-Windows-x64-1.0.2").exists(),
            "the staging folder around the tree was left behind",
        )
        assertFalse(olderStaging.exists(), "an older update's staging was left behind")
    }

    // --------------------------------------------------- leftover pruning

    /** Age a whole tree, folders included: the prune reads the newest timestamp
     *  anywhere inside one, and a folder written to after its files is not old. */
    private fun age(root: File, millis: Long) {
        root.walkTopDown().forEach { it.setLastModified(millis) }
    }

    @Test
    fun `an abandoned download and an abandoned staging tree are reclaimed`() {
        val dir = tempDir("prune")
        val longAgo = System.currentTimeMillis() - 6 * 60 * 60 * 1000L

        val archive = File(dir, "SEKAI-Windows-x64-1.0.2.zip")
        write(archive, "an update nobody installed")
        val partial = File(dir, "SEKAI-Windows-x64-1.0.3.zip.part")
        write(partial, "a download that was killed")
        val staging = File(dir, "staged-SEKAI-Windows-x64-1.0.2/SEKAI")
        write(File(staging, "SEKAI.exe"), "a tree that was never swapped in")
        write(File(staging, "app/composeApp-desktop.jar"), "jar")
        val script = File(dir, "apply-update.cmd")
        write(script, "@echo off")
        age(dir, longAgo)

        val deleted = pruneStaleUpdateLeftovers(updatesDir = dir, now = System.currentTimeMillis())

        assertEquals(4, deleted.size, "every abandoned entry is one the prune must report")
        assertFalse(archive.exists(), "the abandoned archive survived")
        assertFalse(partial.exists(), "the killed download's part file survived")
        assertFalse(staging.parentFile.exists(), "the abandoned staging folder survived")
        assertFalse(script.exists(), "the script of an update that never ran survived")
    }

    @Test
    fun `staging a live update is using is left exactly where it is`() {
        val dir = tempDir("prune-live")

        // A swap in progress looks like this: an archive downloaded a moment
        // ago, a tree staged from it, and the script that is going to use both
        // while it waits for the window to exit.
        val archive = File(dir, "SEKAI-Windows-x64-1.0.2.zip")
        write(archive, "downloaded just now")
        val staging = File(dir, "staged-SEKAI-Windows-x64-1.0.2/SEKAI")
        write(File(staging, "SEKAI.exe"), "staged just now")
        write(File(dir, "apply-update.cmd"), "@echo off")

        val deleted = pruneStaleUpdateLeftovers(updatesDir = dir, now = System.currentTimeMillis())

        assertTrue(deleted.isEmpty(), "a prune reached into an update that is still running")
        assertTrue(archive.isFile, "the archive of a live update was deleted")
        assertTrue(File(staging, "SEKAI.exe").isFile, "a live update lost the tree it is applying")
    }

    @Test
    fun `a prune deletes nothing outside the updates folder`() {
        val work = tempDir("prune-scope")
        val updates = File(work, "updates").apply { mkdirs() }
        write(File(updates, "staged-SEKAI-Windows-x64-1.0.2/SEKAI/SEKAI.exe"), "staging")
        val install = File(work, "install/SEKAI")
        write(File(install, "SEKAI.exe"), "the installed app")
        age(work, System.currentTimeMillis() - 6 * 60 * 60 * 1000L)

        pruneStaleUpdateLeftovers(updatesDir = updates, now = System.currentTimeMillis())

        assertFalse(File(updates, "staged-SEKAI-Windows-x64-1.0.2").exists(), "its own staging survived")
        assertTrue(File(install, "SEKAI.exe").isFile, "the prune reached outside its own folder")
        // A folder that is not there is not an error: the first launch of an
        // install that has never downloaded an update has none.
        assertTrue(
            pruneStaleUpdateLeftovers(
                updatesDir = File(work, "no-such-updates-folder"),
                now = System.currentTimeMillis(),
            ).isEmpty(),
        )
    }

    @Test
    fun `a stray process cannot hold an update open for ever`() {
        val script = windowsPortableReplaceScript(
            stagedDir = "C:\\staged",
            installDir = "C:\\install",
            launcherPath = "C:\\install\\SEKAI.exe",
            // This test's own pid, which is alive for as long as the wait runs:
            // the bounded loop is what has to end it.
            currentPid = ProcessHandle.current().pid(),
            updateArchive = "C:\\updates\\SEKAI-Windows-x64-1.0.2.zip",
        )

        assertTrue(script.contains("if %WAITED% geq 600 goto apply"))
        assertTrue(
            script.indexOf("if %WAITED% geq 600 goto apply") < script.indexOf(":apply"),
            "the bound has to be inside the wait, before the copy",
        )
    }
}
