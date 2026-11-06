package ru.pivoskan.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.CatalogSnapshot
import ru.pivoskan.app.data.CatalogViewModel
import ru.pivoskan.app.data.SourceState
import ru.pivoskan.app.data.SourceStatus
import ru.pivoskan.app.data.Prefs
import ru.pivoskan.app.data.PriceEngine
import ru.pivoskan.app.data.SortMode
import ru.pivoskan.app.data.scan.*
import ru.pivoskan.app.databinding.ActivityMainBinding
import ru.pivoskan.app.ui.BeerAdapter
import ru.pivoskan.app.util.formatMoney
import ru.pivoskan.app.util.SourceLinks
import java.text.DateFormat
import java.util.Date

/** Real official catalogs. Only downloaded prices are shown; filters survive recreation. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: BeerAdapter
    private lateinit var images: ru.pivoskan.app.ui.ProductImageLoader
    private var cityId = ""
    private val selectedStores = mutableSetOf<String>()
    private var search = ""
    private var sort = SortMode.CHEAPEST
    private var updatingChips = false
    private val chips = linkedMapOf<String, Chip>()
    private val handler = Handler(Looper.getMainLooper())
    private val updateSearch = Runnable { render(resetScroll = true) }
    private var pendingScroll: Parcelable? = null
    private lateinit var catalog: CatalogViewModel
    private var snapshot: CatalogSnapshot? = null
    private var cityScan: CityScanSnapshot? = null
    private var loading = false
    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) android.widget.Toast.makeText(this,
            "Фоновый обход работает; разрешите уведомления в настройках, чтобы видеть прогресс и кнопку остановки.", android.widget.Toast.LENGTH_LONG).show()
    }
    private var pendingReport: String? = null
    private val exportReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report = pendingReport
        pendingReport = null
        if (uri != null && report != null) {
            val resolver = applicationContext.contentResolver
            Thread {
                val saved = runCatching { requireNotNull(resolver.openOutputStream(uri)).bufferedWriter().use { it.write(report) } }.isSuccess
                runOnUiThread { android.widget.Toast.makeText(this,
                    if (saved) "Отчёт сохранён" else "Не удалось сохранить отчёт", android.widget.Toast.LENGTH_LONG).show() }
            }.start()
        }
    }
    private val catalogBrowser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (::catalog.isInitialized) {
            // Always reread: a successful save can outlive a browser activity's rotation.
            catalog.restoreImported(cityId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.themeMode)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val city = prefs.cityId?.let(CityCatalog::city)
        if (city == null) {
            startActivity(Intent(this, CityPickerActivity::class.java))
            finish()
            return
        }
        cityId = city.id
        if (savedInstanceState?.getString("city") == cityId) {
            selectedStores.addAll(savedInstanceState.getStringArrayList("stores").orEmpty())
            selectedStores.retainAll(CityCatalog.stores.map { it.id }.toSet())
            search = savedInstanceState.getString("search").orEmpty()
            sort = SortMode.entries.firstOrNull { it.name == savedInstanceState.getString("sort") } ?: SortMode.CHEAPEST
            @Suppress("DEPRECATION")
            pendingScroll = savedInstanceState.getParcelable("scroll")
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        binding.toolbar.title = city.name
        binding.toolbar.menu.add("Сохранить отчёт об источниках").setOnMenuItemClickListener {
            val current = cityScan
            val now = System.currentTimeMillis()
            val events = current?.sources.orEmpty().flatMap { source ->
                listOf(ScanDiagnosticEvent(current?.runId ?: "historical", source.sourceId, null, "discover",
                    source.discovery?.problem?.code ?: if (source.discovery?.complete == true) "OK" else "INCOMPLETE_LIST",
                    now, source.discovery?.shops?.size ?: 0)) + source.results.map { (key, problem) ->
                    ScanDiagnosticEvent(current?.runId ?: "historical", source.sourceId, key.shopId, "load", problem?.code ?: "OK", now, 0)
                }
            }
            val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
            val webVersion = if (android.os.Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.versionName else null
            pendingReport = ScanDiagnostics.export(events, version, android.os.Build.VERSION.RELEASE, webVersion ?: "unknown")
            exportReport.launch("pivoskan-sources.json")
            true
        }
        binding.toolbar.contentDescription = getString(R.string.city_accessibility, city.name)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> if (loading) catalog.cancel() else catalog.load(cityId, force = true)
                R.id.action_change_city -> startActivity(Intent(this, CityPickerActivity::class.java)
                    .putExtra("change_city", true))
                R.id.action_theme -> showThemePicker()
                R.id.action_about -> MaterialAlertDialogBuilder(this).setTitle(R.string.about_title)
                    .setMessage(R.string.about_message).setPositiveButton(R.string.understood, null).show()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        images = ru.pivoskan.app.ui.ProductImageLoader(this)
        adapter = BeerAdapter(images) { BeerDetailsActivity.start(this, it, cityId) }
        if (savedInstanceState?.getString("city") == cityId)
            adapter.expanded.addAll(savedInstanceState.getStringArrayList("expanded").orEmpty())
        binding.beerList.layoutManager = LinearLayoutManager(this)
        binding.beerList.adapter = adapter
        binding.beerList.itemAnimator = null
        buildChips()
        binding.searchInput.setText(search)
        binding.searchInput.doAfterTextChanged {
            search = it?.toString().orEmpty()
            handler.removeCallbacks(updateSearch)
            handler.postDelayed(updateSearch, 180)
        }
        binding.searchInput.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(updateSearch)
                render(resetScroll = true)
                WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
                view.clearFocus()
                true
            } else false
        }
        binding.sortGroup.check(if (sort == SortMode.CHEAPEST) R.id.sortCheapest else R.id.sortDiscount)
        binding.sortGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                sort = if (checkedId == R.id.sortDiscount) SortMode.DISCOUNT else SortMode.CHEAPEST
                render(resetScroll = true)
            }
        }
        binding.emptyState.resetButton.setOnClickListener {
            if (snapshot?.offers.isNullOrEmpty()) catalog.load(cityId, force = true) else resetFilters()
        }
        binding.sourcesButton.setOnClickListener { showSources() }
        binding.storeSelector.setOnClickListener { showShops() }
        catalog = ViewModelProvider(this)[CatalogViewModel::class.java]
        catalog.state.observe(this) { state ->
            cityScan = state.scan?.takeIf { it.cityId == cityId }
            snapshot = state.snapshot?.takeIf { it.cityId == cityId }
            loading = state.loading
            updateSourceControls()
            render(resetScroll = false)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED && !prefsNoticeAsked()) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            notificationsPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        catalog.openScreen(cityId, taskId)
        render(resetScroll = false)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && prefs.cityId != cityId) {
            // Also handles returning from a picker without recreating this activity.
            recreate()
        } else if (::catalog.isInitialized) catalog.openScreen(cityId, taskId)
    }

    private fun buildChips() {
        val labels = listOf("" to getString(R.string.all_stores)) + CityCatalog.stores.map { it.id to it.name }
        labels.forEach { (storeId, label) ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isCheckedIconVisible = false
                setEnsureMinTouchTargetSize(true)
                setOnCheckedChangeListener { _, checked ->
                    if (!updatingChips) {
                        if (storeId.isEmpty()) selectedStores.clear()
                        else if (checked) selectedStores.add(storeId) else selectedStores.remove(storeId)
                        syncChips()
                        render(resetScroll = true)
                    }
                }
            }
            chips[storeId] = chip
            binding.storeChips.addView(chip)
        }
        syncChips()
    }

    private fun syncChips() {
        updatingChips = true
        chips.forEach { (store, chip) -> chip.isChecked = if (store.isEmpty()) selectedStores.isEmpty() else store in selectedStores }
        updatingChips = false
    }

    private fun render(resetScroll: Boolean) {
        handler.removeCallbacks(updateSearch)
        val rows = PriceEngine.products(cityScan ?: ru.pivoskan.app.data.scan.CityScanSnapshot(cityId, null), selectedStores, search, sort)
        val minimum = rows.mapNotNull { it.minimum?.priceKopecks }.minOrNull()
        binding.resultCount.text = if (rows.isEmpty()) getString(R.string.results_none)
            else "Товаров: ${rows.size}" + (minimum?.let { " · от ${formatMoney(it)}" } ?: "")
        binding.beerList.isVisible = rows.isNotEmpty()
        binding.emptyState.root.isVisible = rows.isEmpty()
        val noData = snapshot?.offers.isNullOrEmpty()
        binding.emptyState.emptyTitle.setText(when {
            noData && loading -> R.string.live_loading_title
            noData -> R.string.live_empty_title
            else -> R.string.empty_title
        })
        binding.emptyState.emptySubtitle.setText(when {
            noData && loading -> R.string.live_loading_subtitle
            noData -> R.string.live_empty_subtitle
            else -> R.string.empty_subtitle
        })
        binding.emptyState.resetButton.setText(if (noData) R.string.live_retry else R.string.reset_filters)
        binding.emptyState.resetButton.isEnabled = !noData || !loading
        adapter.submitList(rows) {
            val scroll = pendingScroll
            if (scroll != null) {
                binding.beerList.layoutManager?.onRestoreInstanceState(scroll)
                pendingScroll = null
            } else if (resetScroll && rows.isNotEmpty()) {
                binding.beerList.scrollToPosition(0)
            }
        }
    }
    private fun prefsNoticeAsked() = getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)

    private fun resetFilters() {
        selectedStores.clear()
        search = ""
        sort = SortMode.CHEAPEST
        syncChips()
        binding.searchInput.setText("")
        binding.sortGroup.check(R.id.sortCheapest)
        render(resetScroll = true)
    }

    private fun updateSourceControls() {
        val value = snapshot
        binding.loadingProgress.isVisible = loading
        binding.toolbar.menu.findItem(R.id.action_refresh).apply {
            isEnabled = true
            title = if (loading) "Остановить обход" else "Обновить цены"
        }
        val magnit = cityScan?.sources?.find { it.sourceId == "magnit" }
        binding.storeSelector.text = if (magnit?.discovery != null)
            "Магнит: ${magnit.succeeded}/${magnit.discovery.shops.size} · адреса" else "Магнит: поиск адресов"
        binding.storeSelector.isEnabled = magnit != null
        val available = value?.sources.orEmpty().count { it.status == SourceStatus.AVAILABLE || it.status == SourceStatus.CACHED }
        binding.sourcesButton.text = getString(R.string.source_button, available, CityCatalog.stores.size)
        binding.snapshotLabel.text = when {
            loading -> cityScan?.sources?.firstOrNull { it.current != null }?.let { source ->
                val address = source.discovery?.shops?.find { it.id == source.current?.shopId }?.address
                address?.let { "Проверяем: $it" } ?: "Обновляем каталоги города…"
            } ?: "Ищем магазины выбранного города…"
            value != null && value.offers.isNotEmpty() -> {
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value.updatedAt))
                if (value.fromCache) getString(R.string.live_cached) else getString(R.string.live_updated, time)
            }
            else -> getString(R.string.live_empty_title)
        }
    }

    private fun sourceDescription(source: SourceState): String {
        if (source.storeId == "dixy") {
            if (source.status == SourceStatus.CACHED) return getString(R.string.source_catalog_cached, source.offerCount)
            if (source.status == SourceStatus.AVAILABLE) return getString(R.string.source_catalog_available, source.offerCount)
        }
        return when (source.status) {
            SourceStatus.LOADING -> getString(R.string.source_loading)
            SourceStatus.AVAILABLE -> getString(R.string.source_available, source.offerCount)
            SourceStatus.EMPTY -> getString(R.string.source_empty)
            SourceStatus.BLOCKED -> getString(R.string.source_blocked)
            SourceStatus.ERROR -> getString(R.string.source_error)
            SourceStatus.CACHED -> getString(R.string.source_cached, source.offerCount)
            SourceStatus.CATALOG -> getString(R.string.source_catalog)
        }
    }

    private fun scanSummary(source: SourceProgress?): String {
        if (source == null) return "Ожидает запуска"
        val phase = when (source.phase) {
            SourcePhase.WAITING -> "В очереди"
            SourcePhase.RUNNING -> "Сканирование"
            SourcePhase.NEEDS_LOGIN -> "Нужен вход"
            SourcePhase.NEEDS_ACTION -> "Нужна проверка на сайте"
            SourcePhase.COMPLETE -> "Проверка завершена"
            SourcePhase.PARTIAL -> "Частичный результат"
            SourcePhase.UNAVAILABLE -> "Автоматическая загрузка недоступна"
            SourcePhase.CANCELLED -> "Обход остановлен"
        }
        if (source.discovery?.scope == ru.pivoskan.app.data.PriceScope.CITY_CATALOG)
            return "$phase · каталог города, без подтверждения адресов"
        return "$phase · проверено ${source.succeeded}/${source.discovery?.shops?.size ?: 0}, ошибок ${source.failed}"
    }

    private fun problemText(problem: SourceProblem?): String = when (problem?.kind) {
        ProblemKind.NETWORK -> "Ошибка сети; прежние цены сохранены"
        ProblemKind.HTTP -> "Ответ сайта: ${problem.code}"
        ProblemKind.NEEDS_LOGIN -> "Требуется вход на официальном сайте"
        ProblemKind.NEEDS_ACTION -> "Сайт ограничил запросы или требует проверки: ${problem.code}"
        ProblemKind.UNSUPPORTED -> "Способ автоматической загрузки ещё не подтверждён"
        ProblemKind.INVALID_DATA -> "Не удалось подтвердить адрес, цены или полноту данных"
        ProblemKind.LIMIT -> "Достигнут предел обхода; результат частичный"
        ProblemKind.STORAGE -> "Не удалось сохранить данные на телефоне"
        ProblemKind.CANCELLED -> "Запрос отменён"
        null -> "Проверено"
    }

    private fun showSources() {
        val lines = CityCatalog.stores.map { store ->
            "${store.name}: ${scanSummary(cityScan?.sources?.find { it.sourceId == store.id })}"
        }
        MaterialAlertDialogBuilder(this).setTitle("Источники и охват")
            .setItems(lines.toTypedArray()) { _, index -> showSourceDetails(CityCatalog.stores[index].id) }
            .setPositiveButton(R.string.understood, null)
            .setNegativeButton(if (loading) "Остановить обход" else "Обновить цены") { _, _ ->
                if (loading) catalog.cancel() else catalog.refresh()
            }.show()
    }

    private fun showSourceDetails(sourceId: String) {
        val store = CityCatalog.store(sourceId) ?: return
        val source = cityScan?.sources?.find { it.sourceId == sourceId }
        val lines = mutableListOf(scanSummary(source))
        if (sourceId == "magnit") lines += "Проверяется формат «Магнит у дома». Другие форматы сети пока не подтверждены."
        source?.discovery?.let { discovery ->
            if (!discovery.complete) lines += "Полнота списка магазинов не подтверждена."
            discovery.problem?.let { lines += problemText(it) }
            discovery.shops.forEach { shop ->
                val key = CatalogKey(cityId, sourceId, shop.id)
                val result = when {
                    source.current == key -> "Проверяем…"
                    source.results.containsKey(key) -> problemText(source.results[key])
                    else -> "Не проверен в этом обходе"
                }
                lines += "${shop.address}\n$result"
            }
        }
        val url = when (sourceId) {
            "magnit" -> "https://magnit.ru/catalog/41183-pivo_kokteyli_alkogolcorenew"
            "fixprice" -> "https://fix-price.com/catalog/produkty-i-napitki/alkogolnaya-produktsiya"
            "dixy" -> CatalogBrowserActivity.CATALOG_URL
            else -> "https://5ka.ru/catalog/"
        }
        MaterialAlertDialogBuilder(this).setTitle(store.name).setMessage(lines.joinToString("\n\n"))
            .setPositiveButton("Открыть каталог") { _, _ -> SourceLinks.open(this, url) }
            .setNegativeButton(R.string.understood, null).show()
    }

    private fun showShops() = showSourceDetails("magnit")

    private fun showThemePicker() {
        val modes = intArrayOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
        val names = arrayOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))
        MaterialAlertDialogBuilder(this).setTitle(R.string.choose_theme)
            .setSingleChoiceItems(names, modes.indexOf(prefs.themeMode).coerceAtLeast(0)) { dialog, which ->
                prefs.themeMode = modes[which]
                dialog.dismiss()
                AppCompatDelegate.setDefaultNightMode(modes[which])
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::binding.isInitialized) {
            outState.putString("city", cityId)
            if (::adapter.isInitialized) outState.putStringArrayList("expanded", ArrayList(adapter.expanded))
            outState.putStringArrayList("stores", ArrayList(selectedStores))
            outState.putString("search", search)
            outState.putString("sort", sort.name)
            outState.putParcelable("scroll", binding.beerList.layoutManager?.onSaveInstanceState())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::images.isInitialized) images.close()
        handler.removeCallbacks(updateSearch)
        super.onDestroy()
    }
}
