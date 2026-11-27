package app.universalmedia.feature.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import app.universalmedia.core.model.AppSettings
import org.json.JSONArray
import org.json.JSONTokener

/** Callbacks from WebView into the browser state holder. */
interface BrowserEvents {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String, title: String)
    fun onUrlChanged(tabId: String, url: String)
    fun onTitleChanged(tabId: String, title: String)
    fun onProgress(tabId: String, progress: Int)
    fun onNavigationState(tabId: String, canGoBack: Boolean, canGoForward: Boolean)

    /** Invoked on a background thread for every resource the page requests. */
    fun onResourceRequest(tabId: String, url: String, mimeHint: String?)

    /** Media element sources found by the in-page probe. */
    fun onPageMedia(tabId: String, urls: List<String>)

    /** The page's HTML, scanned for stream links embedded in player configuration. */
    fun onPageSource(tabId: String, html: String)
}

/**
 * Activity-scoped owner of one WebView per tab, so pages keep their state while
 * the user moves between Browser, Library, etc. Destroy with [destroyAll].
 */
class WebViewPool(private val context: Context) {

    class FullscreenVideo(val view: View, val callback: WebChromeClient.CustomViewCallback)

    private val views = HashMap<String, WebView>()

    /** Non-null while a page shows a video in fullscreen. Observed by Compose. */
    var fullscreen by mutableStateOf<FullscreenVideo?>(null)
        private set

    fun obtain(tabId: String, events: BrowserEvents): WebView =
        views.getOrPut(tabId) { create(tabId, events) }

    fun peek(tabId: String): WebView? = views[tabId]

    fun retainOnly(tabIds: Set<String>) {
        (views.keys - tabIds).forEach(::release)
    }

    fun release(tabId: String) {
        views.remove(tabId)?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    fun destroyAll() {
        views.keys.toList().forEach(::release)
    }

    fun exitFullscreen() {
        fullscreen?.callback?.onCustomViewHidden()
        fullscreen = null
    }

    fun applySettings(webView: WebView, settings: AppSettings) {
        webView.settings.javaScriptEnabled = settings.javascriptEnabled
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !settings.blockThirdPartyCookies)
    }

    /** Re-runs the in-page media probe, e.g. after the user started a player. */
    fun scanForMedia(tabId: String, events: BrowserEvents) {
        val webView = views[tabId] ?: return
        webView.evaluateJavascript(MEDIA_PROBE_JS) { events.onPageMedia(tabId, parseProbeResult(it)) }
        readPageSource(webView, tabId, events)
    }

    private fun readPageSource(webView: WebView, tabId: String, events: BrowserEvents) {
        if (!webView.settings.javaScriptEnabled) return
        webView.evaluateJavascript(PAGE_SOURCE_JS) { raw ->
            val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            if (!html.isNullOrEmpty()) events.onPageSource(tabId, html)
        }
    }

    /** Wipes cookies, site storage, caches and back/forward history for every tab. */
    fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        views.values.forEach {
            it.clearCache(true)
            it.clearHistory()
            it.clearFormData()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(tabId: String, events: BrowserEvents): WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }
        installLiveProbe(this, tabId, events)

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let { events.onPageStarted(tabId, it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == null || url == null) return
                events.onPageFinished(tabId, url, view.title.orEmpty())
                events.onNavigationState(tabId, view.canGoBack(), view.canGoForward())
                readPageSource(view, tabId, events)
                // Many sites add their player a moment after the page loads; look again once.
                view.postDelayed({ if (view.url == url && views[tabId] === view) readPageSource(view, tabId, events) }, 4_000)
                if (view.settings.javaScriptEnabled && !liveProbeSupported) {
                    view.evaluateJavascript(MEDIA_PROBE_JS) { events.onPageMedia(tabId, parseProbeResult(it)) }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (view == null || url == null) return
                events.onUrlChanged(tabId, url)
                events.onNavigationState(tabId, view.canGoBack(), view.canGoForward())
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                // Observe only: the request continues untouched to the network.
                request?.url?.toString()?.let { events.onResourceRequest(tabId, it, null) }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase() ?: return false
                // Stay inside the browser for web content; block app-launching schemes (intent:, market:...).
                return scheme !in setOf("http", "https", "about", "data", "blob")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                events.onProgress(tabId, newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                events.onTitleChanged(tabId, title.orEmpty())
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                fullscreen = FullscreenVideo(view, callback)
            }

            override fun onHideCustomView() {
                fullscreen = null
            }
        }
    }

    /**
     * Installs a script that runs before page scripts in every frame. It reports
     * media element sources (including players added later), and manifest
     * responses fetched by JavaScript players whose URLs have no telling
     * extension. Messages are treated as untrusted hints: each URL still goes
     * through the classifier and engine like any other request.
     */
    private fun installLiveProbe(webView: WebView, tabId: String, events: BrowserEvents) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        WebViewCompat.addWebMessageListener(webView, PROBE_OBJECT, setOf("*")) { _, message, _, _, _ ->
            val data = message.data ?: return@addWebMessageListener
            if (data.length > 4096) return@addWebMessageListener
            runCatching {
                val json = JSONObject(data)
                val url = json.optString("u")
                val mime = json.optString("t").takeIf { it.isNotBlank() && it != "null" }
                if (url.startsWith("http")) events.onResourceRequest(tabId, url, mime)
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, LIVE_PROBE_JS, setOf("*"))
    }

    private val liveProbeSupported: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }

    companion object {
        private const val PROBE_OBJECT = "UniversalMediaProbe"

        /** Returns the page HTML (capped at 3 MB), including inline player configuration. */
        private const val PAGE_SOURCE_JS =
            "(function(){try{var h=document.documentElement.outerHTML;return h.length>3000000?h.slice(0,3000000):h;}catch(e){return '';}})();"

        private const val LIVE_PROBE_JS = """
            (function() {
              if (window.__umProbe) return;
              window.__umProbe = true;
              var P = window.UniversalMediaProbe;
              if (!P) return;
              var sent = {}, count = 0;
              function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return null; } }
              function send(u, t) {
                try {
                  if (!u || typeof u !== 'string' || count > 200) return;
                  if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
                  u = abs(u); if (!u) return;
                  var k = u + '|' + (t || '');
                  if (sent[k]) return;
                  sent[k] = 1; count++;
                  P.postMessage(JSON.stringify({ u: u, t: t || null }));
                } catch (e) {}
              }
              function isManifest(t) { return !!t && /mpegurl|dash\+xml/i.test(t); }
              function scan() {
                try {
                  var els = document.querySelectorAll('video, audio, video source, audio source');
                  for (var i = 0; i < els.length; i++) send(els[i].currentSrc || els[i].src, null);
                } catch (e) {}
              }
              ['play', 'loadedmetadata'].forEach(function(name) {
                document.addEventListener(name, function(e) {
                  var el = e.target;
                  if (el && (el.currentSrc || el.src)) send(el.currentSrc || el.src, null);
                }, true);
              });
              try {
                if (window.fetch) {
                  var origFetch = window.fetch;
                  window.fetch = function() {
                    var p = origFetch.apply(this, arguments);
                    try {
                      p.then(function(r) {
                        try { var t = r.headers.get('content-type'); if (isManifest(t)) send(r.url, t.split(';')[0]); } catch (e) {}
                      }, function() {});
                    } catch (e) {}
                    return p;
                  };
                }
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                  try {
                    var xhr = this;
                    xhr.addEventListener('load', function() {
                      try {
                        var t = xhr.getResponseHeader('content-type');
                        if (isManifest(t)) send(xhr.responseURL || String(url), t.split(';')[0]);
                      } catch (e) {}
                    });
                  } catch (e) {}
                  return origOpen.apply(this, arguments);
                };
              } catch (e) {}
              var timer = null;
              function schedule() { if (timer) return; timer = setTimeout(function() { timer = null; scan(); }, 600); }
              function observe() {
                try {
                  new MutationObserver(schedule).observe(document.documentElement, {
                    childList: true, subtree: true, attributes: true, attributeFilter: ['src']
                  });
                  scan();
                } catch (e) {}
              }
              if (document.documentElement) observe(); else document.addEventListener('DOMContentLoaded', observe);
            })();
        """

        /** Collects the sources of <video>/<audio> elements; returns a JSON array string. */
        private const val MEDIA_PROBE_JS = """
            (function() {
              try {
                var out = [];
                document.querySelectorAll('video, audio, video source, audio source').forEach(function(e) {
                  var s = e.currentSrc || e.src;
                  if (s && s.indexOf('blob:') !== 0 && out.indexOf(s) < 0) out.push(s);
                });
                return JSON.stringify(out);
              } catch (err) { return '[]'; }
            })();
        """

        internal fun parseProbeResult(raw: String?): List<String> = try {
            val inner = raw?.let { JSONTokener(it).nextValue() } as? String ?: return emptyList()
            val array = JSONArray(inner)
            List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
