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
 * The catalogue, the episode rows and every stream come from `server/`, which
 * is a sibling of this fork inside the project — the same Node process the web
 * client and the phone talk to. A desktop app that needs a second terminal to
 * be useful is not a desktop app, so the window brings it up itself and waits
 * for a real answer before the first screen asks for anything.
 *
 * Only a server this app started is ever stopped: if something else already
 * answers on the port — a dev server in a terminal, say — it is left alone,
 * because it is not ours to end.
 */
object HanimeServer {

    /** Where the app expects its own source; also what the addon is seeded on. */
    const val BASE = "http://127.0.0.1:8787"

    private var child: Process? = null

    /** Where the server lives, from the app's working directory outwards. */
    private fun script(): File? {
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
            println("hanime-server: no server script found near ${File(".").absolutePath}")
            return false
        }

        child = runCatching {
            ProcessBuilder("node", script.absolutePath)
                .directory(script.parentFile.parentFile)      // ../server
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .apply { environment()["PORT"] = "8787" }
                .start()
        }.getOrNull()

        if (child == null) {
            println("hanime-server: could not start node; is it on PATH?")
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
