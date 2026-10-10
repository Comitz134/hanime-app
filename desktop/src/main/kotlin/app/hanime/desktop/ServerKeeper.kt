package app.hanime.desktop

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The server the window needs, started by the window when it is not there.
 *
 * Only a server this app started is ever stopped: if something else is already
 * answering on the address — a dev server in a terminal, say — it is left
 * alone, because it is not ours to end.
 */
object ServerKeeper {

    private var child: Process? = null

    /** Where the server lives, from the desktop project's own directory up. */
    private fun script(): File? {
        val candidates = listOf(
            File(System.getProperty("hanime.server.script") ?: ""),
            File("../server/src/server.mjs"),
            File("server/src/server.mjs"),
            File(System.getProperty("user.dir"), "../server/src/server.mjs"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun listening(): Boolean = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build()
        val request = HttpRequest.newBuilder(URI.create(Api.base + "/api/videos?per_page=1"))
            .timeout(Duration.ofMillis(1500))
            .GET()
            .build()
        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    /** True when a server answers by the time this returns (or none was needed). */
    fun ensure(): Boolean {
        if (listening()) return true

        val script = script()
        if (script == null) {
            println("hanime-desktop: no server script found near ${File(".").absolutePath}")
            return false
        }

        val port = URI.create(Api.base).port.takeIf { it > 0 } ?: 8787
        child = runCatching {
            ProcessBuilder("node", script.absolutePath)
                .directory(script.parentFile.parentFile)     // ../server
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .apply { environment()["PORT"] = port.toString() }
                .start()
        }.getOrNull()

        if (child == null) {
            println("hanime-desktop: could not start node; is it on PATH?")
            return false
        }

        // Wait for it to answer, not merely to exist: the catalogue warms on
        // boot and the first route can take a moment.
        repeat(40) {
            if (listening()) return true
            Thread.sleep(250)
        }
        println("hanime-desktop: the server did not answer on ${Api.base} in 10s")
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
