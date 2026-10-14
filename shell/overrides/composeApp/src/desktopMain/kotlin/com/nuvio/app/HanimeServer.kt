package com.nuvio.app

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The server this app is a window onto, started by the window when it is not
 * already there.
 *
 * The catalogue, the episode rows and every stream come from `server/`, a Node
 * process. A desktop app that needs a second terminal to be useful is not a
 * desktop app, so the window brings it up itself and waits for a real answer
 * before the first screen asks for anything.
 *
 * The server is *bundled inside this app's own jar* (see the
 * `syncHanimeServer` task in build.gradle.kts), so the installed app is
 * standalone: no repo checkout, no dependence on the working directory it was
 * launched from. On first run it is materialised to a writable per-user folder
 * — the app's own install tree is read-only — and Node is launched from there.
 *
 * Only a server this app started is ever stopped: if something else already
 * answers on the port — a dev server in a terminal, say — it is left alone,
 * because it is not ours to end.
 */
object HanimeServer {

    /** Where the app expects its own source; also what the addon is seeded on. */
    const val BASE = "http://127.0.0.1:8787"

    private const val BUNDLE_ROOT = "/hanime-server"
    private const val PORT = "8787"

    private var child: Process? = null

    /**
     * The entry point of the server to launch, and the directory to launch it
     * from. Dev overrides win first (an explicit script, or a repo checkout
     * next to the working directory), so running from source behaves exactly as
     * it always has; the bundled copy is the standalone fallback.
     */
    private fun script(): File? =
        devScript() ?: bundledScript()

    /** A server chosen by the environment or found next to the working directory. */
    private fun devScript(): File? {
        val candidates = listOfNotNull(
            System.getProperty("hanime.server.script")?.takeIf { it.isNotBlank() }?.let(::File),
            System.getenv("HANIME_SERVER_SCRIPT")?.takeIf { it.isNotBlank() }?.let(::File),
            File("../server/src/server.mjs"),
            File("server/src/server.mjs"),
            File("../../server/src/server.mjs"),
            File(System.getProperty("user.dir"), "../server/src/server.mjs"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * The bundled server, materialised to a writable per-user folder. The jar
     * carries it under the hanime-server/ root; a manifest (FILES.txt) names
     * every file, because a jar offers no directory listing to walk.
     *
     * It is written out on every launch. The bundle is a couple of megabytes,
     * so re-extracting costs nothing measurable — and it is the only way a
     * server *code* change ships with the app that carries it (a file-list
     * stamp would miss an edit that keeps the same filenames). The writable
     * state (`data/`, `.session.json`) is not in the bundle, so it is never
     * touched.
     */
    private fun bundledScript(): File? = runCatching {
        val manifestResource = HanimeServer::class.java.getResourceAsStream("$BUNDLE_ROOT/FILES.txt")
            ?: return null
        val manifest = manifestResource.bufferedReader().use { it.readText() }
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (manifest.isEmpty()) return null

        val root = File(serverHome(), "server")
        manifest.forEach { relative ->
            val bytes = HanimeServer::class.java
                .getResourceAsStream("$BUNDLE_ROOT/$relative")
                ?.use { it.readBytes() }
                ?: return null
            val out = File(root, relative)
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }

        File(root, "src/server.mjs").takeIf { it.isFile }
    }.getOrNull()

    /** A writable per-user home for the materialised server, created on demand. */
    private fun serverHome(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home") ?: "."
        val base = when {
            "win" in os -> System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").path
            "mac" in os -> File(home, "Library/Application Support").path
            else -> System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path
        }
        val dir = File(base, "SEKAI")
        dir.mkdirs()
        return dir
    }

    /**
     * Node to launch: an explicit override, then whatever is on PATH, then the
     * usual install locations. A window launched from a shortcut does not always
     * inherit a shell's PATH, so the explicit locations are not a nicety.
     */
    private fun nodeCommand(): String {
        System.getenv("HANIME_NODE")?.takeIf { it.isNotBlank() && File(it).isFile }?.let { return it }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val exe = if ("win" in os) "node.exe" else "node"
        // Is `node` reachable as PATH would resolve it? ProcessBuilder inherits
        // this process's PATH, so a bare name works only if that PATH is intact.
        val onPath = System.getenv("PATH")?.split(File.pathSeparator)
            ?.firstOrNull { File(it, exe).isFile }
        if (onPath != null) return exe
        val known = if ("win" in os) {
            listOf(
                File(System.getenv("ProgramFiles") ?: "C:/Program Files", "nodejs/$exe"),
                File(System.getenv("LOCALAPPDATA") ?: "", "Programs/nodejs/$exe"),
            )
        } else {
            listOf(File("/usr/local/bin/$exe"), File("/usr/bin/$exe"))
        }
        return known.firstOrNull { it.isFile }?.absolutePath ?: exe
    }

    private fun listening(): Boolean = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build()
        val request = HttpRequest.newBuilder(URI.create("$BASE/api/videos?per_page=1"))
            .timeout(Duration.ofMillis(1500))
            .GET()
            .build()
        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    /** True when a server answers by the time this returns (or none was needed). */
    fun ensure(): Boolean {
        if (listening()) {
            println("hanime-server: already answering on $BASE")
            return true
        }

        val script = script()
        if (script == null) {
            println("hanime-server: no server script found and none bundled")
            return false
        }

        child = runCatching {
            ProcessBuilder(nodeCommand(), script.absolutePath)
                .directory(script.parentFile.parentFile)      // the server root
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .apply { environment()["PORT"] = PORT }
                .start()
        }.getOrNull()

        if (child == null) {
            println("hanime-server: could not start node (${nodeCommand()}); is it installed?")
            return false
        }
        Runtime.getRuntime().addShutdownHook(Thread { stop() })

        // Wait for it to answer, not merely to exist: the catalogue warms on
        // boot and the first route can take a moment.
        repeat(40) {
            if (listening()) {
                println("hanime-server: up on $BASE")
                return true
            }
            Thread.sleep(250)
        }
        println("hanime-server: the server did not answer on $BASE in 10s")
        return false
    }

    /** Ends only the server this app started. */
    fun stop() {
        child?.let { process ->
            runCatching {
                process.destroy()
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
        child = null
    }
}

private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
