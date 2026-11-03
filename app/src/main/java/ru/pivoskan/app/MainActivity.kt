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
    private var loading = false
    private val catalogBrowser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (::catalog.isInitialized) {
            // Always reread: a successful save can outlive a browser activity's rotation.
            catalog.restoreImported(cityId)
            catalog.load(cityId, force = true)
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
        binding.toolbar.contentDescription = getString(R.string.city_accessibility, city.name)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> catalog.load(cityId, force = true)
                R.id.action_change_city -> startActivity(Intent(this, CityPickerActivity::class.java)
                    .putExtra("change_city", true))
                R.id.action_theme -> showThemePicker()
                R.id.action_about -> MaterialAlertDialogBuilder(this).setTitle(R.string.about_title)
                    .setMessage(R.string.about_message).setPositiveButton(R.string.understood, null).show()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        adapter = BeerAdapter { BeerDetailsActivity.start(this, it, cityId) }
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
            snapshot = state.snapshot?.takeIf { it.cityId == cityId }
            loading = state.loading
            updateSourceControls()
            render(resetScroll = false)
        }
        catalog.load(cityId)
        render(resetScroll = false)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && prefs.cityId != cityId) {
            // Also handles returning from a picker without recreating this activity.
            recreate()
        }
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
        val rows = PriceEngine.query(snapshot?.offers.orEmpty(), selectedStores, search, sort)
        binding.resultCount.text = if (rows.isEmpty()) getString(R.string.results_none) else getString(
            R.string.results_from, resources.getQuantityString(R.plurals.offer_count, rows.size, rows.size),
            formatMoney(rows.minOf { it.offer.priceKopecks }))
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
        binding.toolbar.menu.findItem(R.id.action_refresh).isEnabled = !loading
        binding.storeSelector.text = value?.selectedShop?.let { getString(R.string.store_selected, it.address) }
            ?: getString(R.string.store_choose)
        binding.storeSelector.isEnabled = !loading && !value?.shops.isNullOrEmpty()
        val available = value?.sources.orEmpty().count { it.status == SourceStatus.AVAILABLE || it.status == SourceStatus.CACHED }
        binding.sourcesButton.text = getString(R.string.source_button, available, CityCatalog.stores.size)
        binding.snapshotLabel.text = when {
            loading -> getString(R.string.live_loading)
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

    private fun showSources() {
        val states = CityCatalog.stores.map { store ->
            val status = snapshot?.sources?.firstOrNull { it.storeId == store.id }
                ?: SourceState(store.id, if (loading && store.id in setOf("magnit", "fixprice")) SourceStatus.LOADING
                    else if (store.id == "dixy") SourceStatus.CATALOG
                    else if (store.id == "pyaterochka") SourceStatus.BLOCKED else SourceStatus.ERROR)
            getString(R.string.source_line, store.name, sourceDescription(status))
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.sources_title).setItems(states.toTypedArray()) { _, index ->
            val store = CityCatalog.stores[index]
            val scope = when (store.id) {
                "magnit" -> getString(R.string.source_scope_address, getString(R.string.source_scope_store), snapshot?.selectedShop?.address.orEmpty())
                "fixprice" -> getString(R.string.source_scope_city)
                "dixy" -> getString(R.string.source_catalog_scope)
                else -> getString(R.string.source_blocked)
            }
            val url = when (store.id) {
                "magnit" -> "https://magnit.ru/catalog/41183-pivo_kokteyli_alkogolcorenew" +
                    (snapshot?.selectedShop?.let { "?shopCode=${it.id}&shopType=1" } ?: "")
                "fixprice" -> "https://fix-price.com/catalog/produkty-i-napitki/alkogolnaya-produktsiya"
                "dixy" -> CatalogBrowserActivity.CATALOG_URL
                else -> "https://5ka.ru/catalog/"
            }
            val dialog = MaterialAlertDialogBuilder(this).setTitle(store.name)
                .setMessage(scope + "\n\n" + getString(R.string.source_more))
                .setNegativeButton(R.string.understood, null)
            if (store.id == "dixy") {
                dialog.setPositiveButton(R.string.source_city_scan) { _, _ ->
                    catalog.pauseRefresh()
                    catalogBrowser.launch(Intent(this, DixyCityScanActivity::class.java)
                        .putExtra("city_id", cityId))
                }.setNeutralButton(R.string.source_catalog_manual) { _, _ ->
                    catalog.pauseRefresh()
                    catalogBrowser.launch(Intent(this, CatalogBrowserActivity::class.java)
                        .putExtra(CatalogBrowserActivity.EXTRA_CITY, cityId))
                }
            } else dialog.setPositiveButton(R.string.source_open) { _, _ -> SourceLinks.open(this, url) }
            dialog.show()
        }.setPositiveButton(R.string.understood, null).show()
    }

    private fun showShops() {
        val value = snapshot ?: return
        if (value.shops.isEmpty()) return
        val selected = value.shops.indexOfFirst { it.id == value.selectedShop?.id }
        MaterialAlertDialogBuilder(this).setTitle(R.string.store_dialog)
            .setSingleChoiceItems(value.shops.map { it.address }.toTypedArray(), selected) { dialog, index ->
                dialog.dismiss()
                catalog.load(cityId, shopId = value.shops[index].id, force = true)
            }.setNegativeButton(R.string.understood, null).show()
    }

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
            outState.putStringArrayList("stores", ArrayList(selectedStores))
            outState.putString("search", search)
            outState.putString("sort", sort.name)
            outState.putParcelable("scroll", binding.beerList.layoutManager?.onSaveInstanceState())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateSearch)
        super.onDestroy()
    }
}
