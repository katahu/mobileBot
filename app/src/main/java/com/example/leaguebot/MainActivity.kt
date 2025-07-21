package com.example.leaguebot

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var isFirstLoad = true
    private val handler = Handler(Looper.getMainLooper())

    private fun loadAssetFile(filename: String): String =
        assets.open(filename).bufferedReader().use { it.readText() }

    private fun escapeForJs(text: String): String =
        text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            window.attributes = lp
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(true)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)

        webView.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false // перемещено после инициализации webView
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (HTML, like Gecko) Chrome/115 Mobile Safari/537.36"
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Отмена") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                val input = android.widget.EditText(this@MainActivity)
                input.setText(defaultValue)
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("OK") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("Отмена") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d("WebView", "Страница начала загружаться: $url")

                if (isFirstLoad) {
                    handler.postDelayed({ view?.let { performInjection(it) } }, 100)
                } else {
                    view?.let { performInjection(it) }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isFirstLoad = false
                handler.postDelayed({ view?.let { injectIntoShadowDOM(it) } }, 300)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.loadUrl("https://game.league17.ru")
    }

    private fun performInjection(webView: WebView) {
        try {
            val cssRaw = escapeForJs(loadAssetFile("bundle.css"))
            val jsInjectCSS = """
                (function() {
                    if (document.querySelector('style[data-injected="early-css"]')) return;
                    var style = document.createElement('style');
                    style.textContent = "$cssRaw";
                    style.setAttribute('data-injected', 'early-css');
                    if (!document.head) {
                        document.head = document.createElement('head');
                        if (document.documentElement) {
                            document.documentElement.insertBefore(document.head, document.body);
                        }
                    }
                    document.head.appendChild(style);
                    console.log('CSS инъектирован');
                })();
            """.trimIndent()

            webView.evaluateJavascript(jsInjectCSS, null)

            val jsRaw = loadAssetFile("bundle.js")
            val wrappedJs = """
                (function() {
                    if (window.injectedJSLoaded) return;
                    function executeJS() {
                        var originalAppend = Element.prototype.append;
                        Element.prototype.append = function(...nodes) {
                            if (this && typeof originalAppend === 'function') {
                                return originalAppend.apply(this, nodes);
                            }
                        };
                        try {
                            $jsRaw
                            window.injectedJSLoaded = true;
                            console.log('JS инъектирован');
                        } catch (e) {
                            console.error('Ошибка JS:', e);
                        }
                    }
                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', executeJS);
                    } else {
                        executeJS();
                    }
                })();
            """.trimIndent()

            webView.evaluateJavascript(wrappedJs, null)
        } catch (e: Exception) {
            Log.e("WebView", "Ошибка инъекции", e)
        }
    }

    private fun injectIntoShadowDOM(webView: WebView) {
        val cssRaw = escapeForJs(loadAssetFile("bundle.css"))

        val jsShadowInjection = """
            (function(cssText) {
                function applyStyles(root) {
                    if (root.querySelector('style[data-injected="shadow-css"]')) return;
                    var style = document.createElement('style');
                    style.textContent = cssText;
                    style.setAttribute('data-injected', 'shadow-css');
                    root.appendChild(style);
                }
                function traverse(node) {
                    try {
                        if (node.shadowRoot) {
                            applyStyles(node.shadowRoot);
                            node.shadowRoot.querySelectorAll('*').forEach(traverse);
                        }
                        if (node.querySelectorAll) {
                            node.querySelectorAll('*').forEach(traverse);
                        }
                    } catch (e) {}
                }
                traverse(document);
            })("$cssRaw");
        """.trimIndent()

        webView.evaluateJavascript(jsShadowInjection, null)
    }
}
