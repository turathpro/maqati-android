package com.maqati.app

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * One job: show the Maqati page that your own phone is already serving (through Termux) inside a
 * real app window — a proper icon, no address bar, file picking and downloads that work, and a
 * friendly screen if the server (Termux) hasn't been started yet.
 *
 * It does NOT bundle Python, ffmpeg or yt-dlp itself — that stays exactly as it is today, running
 * inside Termux. Which means: nothing here ever needs rebuilding when Maqati itself gets updated
 * (new features, fixes, fonts…) — update the app files the normal way (the in-app "Update" button,
 * or a new zip) and this wrapper just shows whatever is current, automatically, every time you open it.
 */
class MainActivity : AppCompatActivity() {

    // Matches app/main.py's default MAQATI_PORT. If you changed the port, change it here too.
    private val startUrl = "http://127.0.0.1:8765/"

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var fallback: View

    private var filePickCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var filePicker: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        fallback = findViewById(R.id.fallback)

        filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePickCallback
            filePickCallback = null
            if (cb == null) return@registerForActivityResult
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                cb.onReceiveValue(null)
                return@registerForActivityResult
            }
            val uris = if (data.clipData != null) {
                (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri }.toTypedArray()
            } else {
                data.data?.let { arrayOf(it) } ?: arrayOf()
            }
            cb.onReceiveValue(uris)
        }

        setupWebView()
        setupFallbackButtons()

        swipe.setOnRefreshListener {
            webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        load()
    }

    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportMultipleWindows(false)
        s.builtInZoomControls = false

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams?
            ): Boolean {
                filePickCallback?.onReceiveValue(null)
                filePickCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    filePicker.launch(Intent.createChooser(intent, "اختر ملفًا"))
                    true
                } catch (e: ActivityNotFoundException) {
                    filePickCallback = null
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                swipe.visibility = View.VISIBLE
                fallback.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only treat it as "server's down" when it's the *page itself* that failed,
                // not some unrelated sub-resource (an image, a font, a background poll...).
                if (request == null || !request.isForMainFrame) return
                showFallback()
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        guessFileName(url, contentDisposition)
                    )
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "ينزّل إلى مجلد Downloads…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("Maqati", "download failed", e)
                Toast.makeText(this, "تعذّر بدء التنزيل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun guessFileName(url: String, contentDisposition: String?): String {
        contentDisposition?.let {
            val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(it)
            if (m != null) return Uri.decode(m.groupValues[1])
        }
        return Uri.parse(url).lastPathSegment ?: "maqati-file"
    }

    private fun setupFallbackButtons() {
        findViewById<Button>(R.id.btn_retry).setOnClickListener { load() }
        findViewById<Button>(R.id.btn_open_termux).setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage("com.termux")
            if (intent != null) {
                startActivity(intent)
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.termux")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
                }
            }
        }
    }

    private fun load() {
        fallback.visibility = View.GONE
        swipe.visibility = View.GONE
        progress.visibility = View.VISIBLE
        webView.loadUrl(startUrl)
    }

    private fun showFallback() {
        progress.visibility = View.GONE
        swipe.isRefreshing = false
        swipe.visibility = View.GONE
        fallback.visibility = View.VISIBLE
    }
}
