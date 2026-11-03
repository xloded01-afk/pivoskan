package ru.pivoskan.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.JsonParser
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.RemoteShop
import ru.pivoskan.app.data.remote.DixyShops
import java.net.URI

/** One visible, ordinary official-site session. No bridge, cookie reads or private request APIs. */
class DixyCityScanActivity : AppCompatActivity() {
    private lateinit var model: DixyCityScanModel
    private lateinit var city: City
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var cancel: MaterialButton
    private lateinit var progress: ProgressBar
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var phase = "boot"
    private var deadline = 0L
    private var started = 0L
    private var script = ""
    private var extractor = ""
    private var finishAfterSave = false
    private val current: RemoteShop? get() = model.shops.getOrNull(model.progress.attempted)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        city = intent.getStringExtra(EXTRA_CITY)?.let(CityCatalog::city) ?: run { finish(); return }
        model = ViewModelProvider(this)[DixyCityScanModel::class.java]
        enableEdgeToEdge()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = "Дикси · ${city.name}"
            subtitle = "dixy.ru · все публичные магазины города"
            setNavigationIcon(R.drawable.ic_back)
            navigationContentDescription = "Назад"
            setNavigationOnClickListener { leave() }
        }
        root.addView(toolbar)
        status = TextView(this).apply { setPadding(24, 12, 24, 8); accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        details = TextView(this).apply {
            setPadding(24, 0, 24, 8); maxHeight = (160 * resources.displayMetrics.density).toInt()
            movementMethod = android.text.method.ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        root.addView(status); root.addView(details)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progress, LinearLayout.LayoutParams(-1, 8))
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        cancel = MaterialButton(this).apply { text = "Отменить"; setOnClickListener { if (model.stopped) leave() else stop("Отменено пользователем") } }
        root.addView(cancel)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        with(web.settings) {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false); setSupportMultipleWindows(false)
            useWideViewPort = true; loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        script = assets.open("DixyCityScan.js").bufferedReader().use { it.readText() }
        extractor = assets.open("dixy-catalog.js").bufferedReader().use { it.readText() }.trim().removeSuffix(";")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (!official(request.url.toString())) { stop("Внешний переход остановлен"); return true }
                if (phase !in listOf("boot", "load")) { stop("Навигация изменилась; сканирование остановлено"); return true }
                return false
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!model.stopped && phase in listOf("scan", "select", "discover")) stop("Документ изменился; текущий магазин не сохранён")
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (model.stopped) return
                if (phase == "clear" && url == "about:blank") {
                    phase = "load"; web.loadUrl(CATALOG_URL); return
                }
                if (url == null || !official(url) || phase !in listOf("boot", "load")) return
                val token = generation
                web.evaluateJavascript(script) {
                    if (!alive(token)) return@evaluateJavascript
                    if (phase == "boot") command("discover", "window.__dixyCityScan.discover()", 40000)
                    else if (phase == "load") {
                        val address = Gson().toJson(current?.address ?: return@evaluateJavascript)
                        command("scan", "window.__dixyCityScan.scan($address,function(){return $extractor;})", 190000)
                    }
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) stop("Ошибка загрузки официального сайта")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame || (response.statusCode in listOf(401, 403, 429) &&
                        DixyCityScanState.isRequiredCatalogRequest(request.url.toString())))
                    stop("Сайт вернул HTTP ${response.statusCode}; сканирование остановлено")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                stop("WebView остановлен; сохранены только завершённые магазины"); return true
            }
        }
        model.changes.observe(this) {
            render()
            if (finishAfterSave && !model.saving) { finish(); return@observe }
            if (!model.stopped && phase == "save" && !model.saving) next()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        if (savedInstanceState != null || model.stopped) {
            if (!model.stopped) { model.stopped = true; model.reason = "Сканирование прервано пересозданием экрана" }
            phase = "stopped"; render()
        } else {
            started = SystemClock.elapsedRealtime(); deadline = started + 45000
            render(); main.postDelayed(watchdog, 500); web.loadUrl("https://dixy.ru/")
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!::model.isInitialized || model.stopped) return
            if (SystemClock.elapsedRealtime() - started > 45 * 60 * 1000L) stop("Достигнут предел 45 минут; остался частичный каталог")
            else if (phase != "save" && SystemClock.elapsedRealtime() > deadline) failed("Истекло время ожидания", phase != "scan")
            if (!model.stopped) main.postDelayed(this, 500)
        }
    }

    private fun alive(token: Int) = token == generation && !model.stopped && !isFinishing && !isDestroyed
    private fun command(nextPhase: String, js: String, timeout: Long) {
        phase = nextPhase; deadline = SystemClock.elapsedRealtime() + timeout
        val token = generation
        web.evaluateJavascript(js) { if (alive(token)) poll(token) }; render()
    }
    private fun poll(token: Int) {
        if (!alive(token)) return
        web.evaluateJavascript("window.__dixyCityScan ? window.__dixyCityScan.status() : null") { encoded ->
            if (!alive(token)) return@evaluateJavascript
            val result = runCatching {
                require(encoded.length <= MAX_BRIDGE_CHARS)
                JsonParser.parseString(JsonParser.parseString(encoded).asString).asJsonObject
            }.getOrElse { stop("Потеряно состояние страницы; магазин не сохранён"); return@evaluateJavascript }
            when (result.get("status")?.asString) {
                "error" -> {
                    val message = result.get("message")?.asString.orEmpty().take(300)
                    val blocked = Regex("HTTP|огранич|провер|captcha|fetch|Network|abort", RegexOption.IGNORE_CASE).containsMatchIn(message)
                    failed(message, phase != "scan" || blocked)
                }
                "done" -> runCatching { received(result.get("value").asString) }.onFailure {
                    failed("Не удалось проверить данные официального сайта", true)
                }
                else -> {
                    if (phase == "scan") render("Страница ${result.get("page")?.asInt ?: 0} · карточек ${result.get("loaded")?.asInt ?: 0}")
                    main.postDelayed({ poll(token) }, 500)
                }
            }
        }
    }
    private fun received(value: String) {
        when (phase) {
            "discover" -> {
                val shops = DixyShops.parse(value, city)
                require(shops.isNotEmpty())
                require(shops.size <= 500) { "Слишком много магазинов" }
                if (shops.map { it.id }.distinct().size != shops.size ||
                    shops.map { DixyCityScanState.normalized(it.address) }.distinct().size != shops.size) {
                    stop("Список содержит повторные ID или адреса; полнота обхода не подтверждена")
                    return
                }
                model.shops = shops; model.progress = DixyCityScanState(shops.size); next()
            }
            "select" -> {
                phase = "clear"; deadline = SystemClock.elapsedRealtime() + 45000
                web.loadUrl("about:blank")
            }
            "scan" -> {
                val shop = requireNotNull(current)
                val address = JsonParser.parseString(value).asJsonObject.get("address").asString
                require(DixyCityScanState.sameAddress(city, shop.address, address))
                phase = "save"; model.save(value, city, shop); render()
            }
        }
    }
    private fun next() {
        if (model.stopped) return
        if (current == null) { stop(if (model.progress.allCompleted) "Все обнаруженные магазины сохранены" else "Обход завершён с ошибками; каталог частичный"); return }
        val shop = requireNotNull(current)
        if (shop.latitude == null || shop.longitude == null || !shop.latitude.isFinite() || !shop.longitude.isFinite()) {
            model.progress.failed(shop.address, "Магазин не поддержан: нет координат")
            render(); main.post { next() }; return
        }
        command("select", "window.__dixyCityScan.select(${Gson().toJson(shop)})", 40000)
    }
    private fun failed(message: String, stopAll: Boolean) {
        if (model.stopped) return
        if (current != null && !model.saving) model.progress.failed(requireNotNull(current).address, message.ifBlank { "Каталог недоступен" })
        if (stopAll || phase != "scan") stop(message) else {
            web.evaluateJavascript("if(window.__dixyCityScan)window.__dixyCityScan.cancel()", null)
            generation++; next()
        }
    }
    private fun stop(reason: String) {
        if (!::model.isInitialized || model.stopped) return
        generation++; model.stopped = true; model.reason = reason; phase = "stopped"
        main.removeCallbacksAndMessages(null)
        web.evaluateJavascript("if(window.__dixyCityScan)window.__dixyCityScan.cancel()", null)
        web.stopLoading(); render()
    }
    private fun render(extra: String = "") {
        val p = model.progress
        progress.isIndeterminate = p.total == 0 && !model.stopped
        if (!progress.isIndeterminate) { progress.max = p.total.coerceAtLeast(1); progress.progress = p.attempted }
        status.text = if (model.stopped) model.reason else when (phase) {
            "boot", "discover" -> "Получаем полный публичный список магазинов…"
            "save" -> "Сохраняем завершённый магазин…"
            else -> "${current?.address.orEmpty()}\n$extra"
        }
        details.text = "Сохранено: ${p.completed}/${p.total} · ошибок/не поддержано: ${p.failures.size} · не завершено: ${p.remaining}" +
            (if (!p.allCompleted) "\nЧастичный каталог. Сохраняются только полностью проверенные магазины." else "") +
            (if (model.saving) "\nЗапись завершённого магазина ещё выполняется." else "") +
            p.failures.entries.joinToString("") { "\n${it.key}: ${it.value}" }
        cancel.text = if (model.stopped) "Закрыть" else "Отменить"
        if (p.completed > 0) setResult(RESULT_OK)
    }
    private fun leave() {
        stop("Обход остановлен; завершённые магазины сохранены")
        if (!model.saving) { finish(); return }
        if (finishAfterSave) return
        finishAfterSave = true
        status.text = "Завершаем атомарную запись магазина перед выходом…"
        cancel.isEnabled = false
        // Do not race the parent's native refresh against an already-started atomic import.
        // Bound the busy presentation; a slow disk remains visible, never reported as saved.
        main.postDelayed({
            if (!isDestroyed && model.saving && finishAfterSave) {
                progress.isIndeterminate = false
                status.text = "Запись занимает больше 30 секунд. Выход произойдёт после завершения записи."
                details.text = "Каталог частичный. Экран остаётся отзывчивым; приложение можно свернуть. Запись ещё не подтверждена."
            }
        }, 30000)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        stop("Сканирование прервано пересозданием экрана; каталог частичный")
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (::web.isInitialized) {
            stop("Сканирование остановлено при закрытии экрана")
            main.removeCallbacksAndMessages(null)
            web.stopLoading(); web.webViewClient = WebViewClient(); web.removeAllViews(); web.destroy()
        }
        super.onDestroy()
    }
    private fun official(value: String) = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "dixy.ru" && uri.userInfo == null && uri.port in listOf(-1, 443)
    }.getOrDefault(false)

    companion object {
        const val EXTRA_CITY = "city_id"
        const val CATALOG_URL = "https://dixy.ru/catalog/alkogolnye-napitki/pivo/"
        private const val MAX_BRIDGE_CHARS = 8 * 1024 * 1024
    }
}
