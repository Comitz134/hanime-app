package com.nuvio.app.features.manga

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The app's own window onto the web.
 *
 * The old shell's WebView tile opened mangafire inside the app, and this is the
 * same thing without the shell: one instance, created for the screen and
 * destroyed with it, so a title opened, left and re-opened does not leave a
 * page-loading WebView behind on a phone with 4 GB of RAM.
 *
 * The JavaScript is on because their title pages are their own bundle; the
 * client is the default one, which is what keeps a navigation inside the page
 * rather than handing the reader to a browser they then have to come back from.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun MangaWebView(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // Pinch-zoom without the legacy on-screen buttons, which would draw
            // a pair of plus/minus keys over the page.
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = WebViewClient()
        }
    }

    // Loaded after composition rather than in `remember`: the instance is built
    // once, and the address may be replaced (a different title, a redirected
    // page) without building another one.
    LaunchedEffect(url) {
        if (webView.url != url) webView.loadUrl(url)
    }

    DisposableEffect(Unit) {
        onDispose {
            // `about:blank` first: destroying a WebView that is still playing
            // audio is how a page keeps talking after the reader has left it.
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
            }
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}
