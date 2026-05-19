package com.openclaw.assistant.ui.terminal

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun TerminalWebView(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val webView = remember {
        @SuppressLint("SetJavaScriptEnabled")
        WebView(context).apply {
            setBackgroundColor(0xFF0B1020.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.textZoom = 100
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val widthCss = (view.width / density).toInt()
                    val heightCss = (view.height / density).toInt()
                    if (widthCss > 0 && heightCss > 0) {
                        view.evaluateJavascript(
                            "if (window.refit) window.refit($widthCss, $heightCss);",
                            null,
                        )
                    }
                }
            }
            addJavascriptInterface(TerminalBridge(viewModel), "AndroidBridge")
            addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                val widthCss = ((right - left) / density).toInt()
                val heightCss = ((bottom - top) / density).toInt()
                if (widthCss > 0 && heightCss > 0) {
                    view.post {
                        (view as WebView).evaluateJavascript(
                            "if (window.refit) window.refit($widthCss, $heightCss);",
                            null,
                        )
                    }
                }
            }
            loadUrl("file:///android_asset/terminal/index.html")
        }
    }

    LaunchedEffect(webView, viewModel) {
        viewModel.outputFlow.collect { output ->
            webView.evaluateJavascript("window.writeTerminal('${output.b64}');", null)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            runCatching {
                webView.loadUrl("about:blank")
                webView.removeJavascriptInterface("AndroidBridge")
                webView.destroy()
            }
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

private class TerminalBridge(
    private val viewModel: TerminalViewModel,
) {
    @JavascriptInterface
    fun onReady(cols: Int, rows: Int) {
        viewModel.onTerminalReady(cols, rows)
    }

    @JavascriptInterface
    fun onInput(data: String) {
        viewModel.sendInput(data)
    }

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) {
        viewModel.resize(cols, rows)
    }
}
