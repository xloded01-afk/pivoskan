package ru.pivoskan.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonParser
import ru.pivoskan.app.data.CatalogImportViewModel
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.databinding.ActivityCatalogBrowserBinding
import ru.pivoskan.app.util.SourceLinks
import java.net.URI

/** A normal official-site session on the phone. Imports public cards only after explicit save. */
class CatalogBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCatalogBrowserBinding
    private lateinit var importer: CatalogImportViewModel
    private var saving = false
    private var navigation = 0

    @SuppressLint("SetJavaScriptEnabled") // Required by the retailer's public catalog; no JS bridge is installed.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val city = intent.getStringExtra(EXTRA_CITY)?.let(CityCatalog::city) ?: run { finish(); return }
        importer = ViewModelProvider(this)[CatalogImportViewModel::class.java]
        binding = ActivityCatalogBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        binding.toolbar.title = getString(R.string.catalog_title, city.name)
        binding.toolbar.subtitle = "dixy.ru"
        binding.toolbar.setNavigationOnClickListener { if (canLeave()) finish() }
        binding.toolbar.menu.add(R.string.catalog_reload).setOnMenuItemClickListener {
            if (!saving && !importer.saving) binding.webView.reload()
            true
        }
        binding.toolbar.menu.add(R.string.source_open_external).setOnMenuItemClickListener {
            SourceLinks.open(this, binding.webView.url?.takeIf(::isCatalogHost) ?: CATALOG_URL)
            true
        }
        binding.instructions.text = getString(R.string.catalog_instructions, city.name)
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, false)
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !isCatalogHost(request.url.toString())

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                navigation++
                binding.progress.isVisible = true
                binding.savePrices.isEnabled = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.progress.isVisible = false
                binding.savePrices.isEnabled = !saving && !importer.saving && url != null && isCatalogHost(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    binding.progress.isVisible = false
                    binding.savePrices.isEnabled = false
                    Toast.makeText(this@CatalogBrowserActivity, R.string.catalog_load_error, Toast.LENGTH_LONG).show()
                }
            }
        }
        importer.state.observe(this) { state ->
            binding.savePrices.isEnabled = !saving && !state.saving && isCatalogHost(binding.webView.url.orEmpty())
            binding.savePrices.setText(if (state.saving) R.string.catalog_saving else R.string.catalog_save)
            state.count?.let { count ->
                importer.consumeResult()
                setResult(RESULT_OK)
                MaterialAlertDialogBuilder(this).setTitle(getString(R.string.catalog_saved, count))
                    .setMessage(R.string.catalog_next_pages)
                    .setPositiveButton(R.string.catalog_back) { _, _ -> finish() }
                    .setNegativeButton(R.string.catalog_continue, null).show()
            }
            if (state.failed) {
                importer.consumeResult()
                showImportError(city.name)
            }
        }
        binding.savePrices.setOnClickListener {
            if (saving || importer.saving || !isCatalogHost(binding.webView.url.orEmpty())) return@setOnClickListener
            saving = true
            binding.savePrices.isEnabled = false
            val page = navigation
            val script = assets.open("dixy-catalog.js").bufferedReader().use { it.readText() }
            binding.webView.evaluateJavascript(script) { encoded ->
                if (page != navigation || isFinishing || isDestroyed) {
                    saving = false
                    return@evaluateJavascript
                }
                saving = false
                val json = runCatching {
                    require(encoded.length <= 512_000)
                    JsonParser.parseString(encoded).asString
                }.getOrNull()
                if (json != null) importer.save(json, city) else {
                    binding.savePrices.isEnabled = true
                    showImportError(city.name)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canLeave()) {
                    if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
                }
            }
        })
        if (savedInstanceState == null || binding.webView.restoreState(savedInstanceState) == null)
            binding.webView.loadUrl(CATALOG_URL)
    }

    private fun isCatalogHost(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "dixy.ru" && uri.userInfo == null && uri.port in listOf(-1, 443)
    }.getOrDefault(false)

    private fun canLeave(): Boolean {
        if (saving || importer.saving) {
            Toast.makeText(this, R.string.catalog_saving, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun showImportError(city: String) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.catalog_not_saved)
            .setMessage(getString(R.string.catalog_import_error, city))
            .setPositiveButton(R.string.understood, null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::binding.isInitialized) binding.webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (::binding.isInitialized) binding.webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) binding.webView.onResume()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.webView.stopLoading()
            binding.webView.removeAllViews()
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CITY = "city_id"
        const val CATALOG_URL = "https://dixy.ru/catalog/alkogolnye-napitki/pivo/"
    }
}
