package app.hanime.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

/**
 * The window.
 *
 * The app is a client, not a server: it talks to `../server` over HTTP, and if
 * that server is not already answering on the configured address it starts it,
 * because a desktop app that needs a second terminal to be useful is not a
 * desktop app. The child is left to die with this process.
 */
fun main() {
    // Posters are remote and numerous: one shared loader, a network fetcher of
    // its own, and a crossfade so a rail does not flash as it fills.
    SingletonImageLoader.setSafe { context: PlatformContext ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
    }

    ServerKeeper.ensure()

    application {
        val state = remember { AppState() }
        Window(
            onCloseRequest = { ServerKeeper.stop(); exitApplication() },
            title = "Hanime",
            state = rememberWindowState(
                size = DpSize(1420.dp, 940.dp),
                position = WindowPosition(Alignment.Center),
            ),
        ) {
            HanimeTheme { AppShell(state) }
        }
    }
}
