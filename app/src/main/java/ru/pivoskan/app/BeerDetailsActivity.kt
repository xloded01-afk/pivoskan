package ru.pivoskan.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.CatalogRepository
import ru.pivoskan.app.data.CatalogSnapshot
import ru.pivoskan.app.data.scan.AndroidCityScanRepository
import ru.pivoskan.app.data.scan.asLegacySnapshot
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.Offer
import ru.pivoskan.app.data.Prefs
import ru.pivoskan.app.data.PriceEngine
import ru.pivoskan.app.data.PriceScope
import ru.pivoskan.app.databinding.ActivityBeerDetailsBinding
import ru.pivoskan.app.ui.CompareAdapter
import ru.pivoskan.app.util.MapsLauncher
import ru.pivoskan.app.util.SourceLinks
import ru.pivoskan.app.util.formatMoney
import ru.pivoskan.app.util.formatVolume
import java.text.DateFormat
import java.util.Date

class BeerDetailsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBeerDetailsBinding
    private var selectedOfferId: String? = null
    private var selectedCityId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = Prefs(this).themeMode.takeIf {
            it == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM ||
                it == AppCompatDelegate.MODE_NIGHT_NO || it == AppCompatDelegate.MODE_NIGHT_YES
        } ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        // Apply the saved preference before AppCompat creates any themed views.
        delegate.localNightMode = mode
        super.onCreate(savedInstanceState)

        val cityId = savedInstanceState?.getString(EXTRA_CITY_ID)
            ?: runCatching { intent.getStringExtra(EXTRA_CITY_ID) }.getOrNull()
        val offerId = savedInstanceState?.getString(EXTRA_OFFER_ID)
            ?: runCatching { intent.getStringExtra(EXTRA_OFFER_ID) }.getOrNull()
        val city = cityId?.let(CityCatalog::city)
        val snapshot = city?.let { AndroidCityScanRepository.get(this).read(it.id).asLegacySnapshot() }
            ?.takeIf { it.cityId == cityId }
        val offer = snapshot?.offers?.firstOrNull { it.id == offerId && it.cityId == cityId }
        if (city == null || snapshot == null || offer == null) {
            Toast.makeText(this, R.string.details_offer_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val navigationScrim = ContextCompat.getColor(this, R.color.bg)
        val legacyNavigationScrim = ContextCompat.getColor(this, R.color.navigation_legacy_scrim)
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(navigationScrim)
                else SystemBarStyle.light(navigationScrim, legacyNavigationScrim)
        )
        binding = ActivityBeerDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        listOf(binding.beerTitle, binding.mapHeading, binding.addressHeading, binding.comparisonHeading)
            .forEach { ViewCompat.setAccessibilityHeading(it, true) }
        bindOffer(offer, city, snapshot)
        if (savedInstanceState == null && animationsEnabled()) {
            binding.detailsScroll.alpha = 0f
            binding.detailsScroll.translationX = 24f * resources.displayMetrics.density
            binding.detailsScroll.animate().alpha(1f).translationX(0f).setDuration(220L).start()
        }
    }

    private fun applyInsets() {
        val root = binding.root
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindOffer(offer: Offer, city: City, snapshot: CatalogSnapshot): Unit = with(binding) {
        selectedOfferId = offer.id
        selectedCityId = city.id
        toolbar.subtitle = city.name
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
        storeDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(offer.store.color)
        }
        storeName.text = offer.store.name
        beerTitle.text = offer.beer.title
        val volume = offer.beer.volumeMl.takeIf { it > 0 }?.let(::formatVolume)
        val packaging = offer.beer.packageName.takeIf { it.isNotBlank() }
        beerMeta.text = when {
            volume != null && packaging != null -> getString(R.string.details_product_meta, volume, packaging)
            volume != null -> volume
            packaging != null -> packaging
            else -> getString(R.string.details_volume_unknown)
        }
        val maximumPrice = offer.maxPriceKopecks?.takeIf { it > offer.priceKopecks }
        price.text = if (maximumPrice != null) {
            getString(R.string.details_price_range, formatMoney(offer.priceKopecks), formatMoney(maximumPrice))
        } else formatMoney(offer.priceKopecks)
        val previousPrice = offer.oldPriceKopecks?.takeIf { it > offer.priceKopecks }
        discountRow.isVisible = previousPrice != null
        oldPrice.text = previousPrice?.let(::formatMoney).orEmpty()
        oldPrice.contentDescription = previousPrice?.let {
            getString(R.string.details_old_price, formatMoney(it))
        }
        if (previousPrice != null) {
            oldPrice.paintFlags = oldPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        // A range does not establish a percentage discount for every listed price.
        discount.isVisible = previousPrice != null && maximumPrice == null && offer.discountPercent > 0
        discount.text = getString(R.string.details_discount, offer.discountPercent)
        cardPrice.isVisible = offer.cardPriceKopecks != null
        cardPrice.text = offer.cardPriceKopecks?.let {
            getString(R.string.details_card_price, formatMoney(it))
        }.orEmpty()
        validUntil.isVisible = !offer.validUntil.isNullOrBlank()
        validUntil.text = offer.validUntil?.takeIf { it.isNotBlank() }?.let {
            getString(R.string.details_valid_until, it)
        }.orEmpty()
        sourceName.text = getString(R.string.details_source, offer.store.name)
        val fetched = offer.fetchedAt.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }
        fetchedAt.text = if (fetched != null) {
            getString(if (offer.cached) R.string.details_fetched_cached else R.string.details_fetched_at, fetched)
        } else getString(R.string.details_fetched_unknown)
        openSource.isEnabled = SourceLinks.isSupported(offer.sourceUrl)
        openSource.setOnClickListener { SourceLinks.open(this@BeerDetailsActivity, offer.sourceUrl) }

        val storeScoped = offer.scope == PriceScope.STORE
        priceScope.text = getString(
            if (storeScoped) R.string.details_scope_store else R.string.details_scope_city, city.name
        )
        val hasStoreAddress = storeScoped && offer.address.isNotBlank()
        // Reset every location view on each comparison selection, including STORE -> CITY_CATALOG.
        mapHeading.isVisible = hasStoreAddress
        mapNote.isVisible = hasStoreAddress
        mapCard.isVisible = hasStoreAddress
        openMaps.isVisible = hasStoreAddress
        copyAddress.isVisible = hasStoreAddress
        addressHeading.isVisible = storeScoped
        address.isVisible = storeScoped
        hours.isVisible = storeScoped && offer.hours.isNotBlank()
        address.text = if (hasStoreAddress) {
            getString(R.string.details_full_address, city.name, offer.address)
        } else getString(R.string.details_address_unknown)
        mapAddress.text = if (hasStoreAddress) address.text else ""
        hours.text = offer.hours
        cityMap.setOnClickListener(null)
        openMaps.setOnClickListener(null)
        copyAddress.setOnClickListener(null)
        if (hasStoreAddress) {
            cityMap.bind(city, offer.store, offer.address)
            cityMap.setOnClickListener { MapsLauncher.open(this@BeerDetailsActivity, offer, city) }
            openMaps.setOnClickListener { MapsLauncher.open(this@BeerDetailsActivity, offer, city) }
            copyAddress.setOnClickListener { copyStoreAddress(offer, city) }
        }

        val alternatives = PriceEngine.alternatives(snapshot.offers, offer.beer.id)
        comparisonSection.isVisible = alternatives.size > 1
        comparisonList.layoutManager = LinearLayoutManager(this@BeerDetailsActivity)
        comparisonList.isNestedScrollingEnabled = false
        comparisonList.itemAnimator = null
        comparisonList.adapter = CompareAdapter(alternatives, offer.id) { alternative ->
            // Reuse this Activity without creating a back stack of comparison selections.
            bindOffer(alternative, city, snapshot)
            detailsScroll.smoothScrollTo(0, 0)
        }
    }

    private fun copyStoreAddress(offer: Offer, city: City) {
        if (offer.scope != PriceScope.STORE || offer.address.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        val copied = if (clipboard == null) false else try {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.details_clip_label),
                getString(R.string.details_copy_payload, offer.store.name, city.name, offer.address)
            ))
            true
        } catch (_: SecurityException) {
            false
        }
        Toast.makeText(this, if (copied) R.string.details_address_copied
            else R.string.details_address_copy_failed, Toast.LENGTH_SHORT).show()
    }

    private fun animationsEnabled() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_OFFER_ID, selectedOfferId)
        outState.putString(EXTRA_CITY_ID, selectedCityId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.detailsScroll.animate().cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_OFFER_ID = "offerId"
        private const val EXTRA_CITY_ID = "cityId"

        @JvmStatic
        fun start(context: Context, offer: Offer, cityId: String) {
            val intent = Intent(context, BeerDetailsActivity::class.java)
                .putExtra(EXTRA_OFFER_ID, offer.id)
                .putExtra(EXTRA_CITY_ID, cityId)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
