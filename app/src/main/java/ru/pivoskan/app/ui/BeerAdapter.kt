package ru.pivoskan.app.ui

import android.content.res.ColorStateList
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.pivoskan.app.R
import ru.pivoskan.app.data.BeerRow
import ru.pivoskan.app.data.Offer
import ru.pivoskan.app.data.PriceScope
import ru.pivoskan.app.databinding.ItemBeerBinding
import ru.pivoskan.app.util.formatMoney
import ru.pivoskan.app.util.formatVolume
import java.util.Locale
import java.text.DateFormat
import java.util.Date

class BeerAdapter(private val onOfferClick: (Offer) -> Unit) :
    ListAdapter<BeerRow, BeerAdapter.Holder>(DIFF) {

    // Stable numeric IDs are assigned without relying on String.hashCode collisions.
    private val rowIds = mutableMapOf<String, Long>()
    private var nextId = 0L
    private val animated = mutableSetOf<String>()

    init {
        setHasStableIds(true)
        stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int): Long =
        rowIds.getOrPut(getItem(position).offer.id) { nextId++ }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemBeerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position), position)

    override fun onViewRecycled(holder: Holder) {
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
        super.onViewRecycled(holder)
    }

    inner class Holder(private val binding: ItemBeerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BeerRow, position: Int) = with(binding) {
            val offer = row.offer
            val context = root.context
            storeDot.backgroundTintList = ColorStateList.valueOf(offer.store.color)
            storeName.text = offer.store.name.uppercase(Locale.ROOT)
            beerName.text = offer.beer.title
            volume.text = if (offer.beer.volumeMl > 0) formatVolume(offer.beer.volumeMl)
                else context.getString(R.string.live_volume_unknown)
            val maximum = offer.maxPriceKopecks?.takeIf { it > offer.priceKopecks }
            price.text = if (maximum != null) context.getString(R.string.live_price_range,
                formatMoney(offer.priceKopecks), formatMoney(maximum)) else formatMoney(offer.priceKopecks)
            oldPrice.text = offer.oldPriceKopecks?.let(::formatMoney).orEmpty()
            oldPrice.paintFlags = oldPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            oldPrice.isVisible = offer.oldPriceKopecks != null && maximum == null
            discountBadge.text = context.getString(R.string.discount_percent, offer.discountPercent)
            discountBadge.isVisible = offer.discountPercent > 0 && maximum == null
            bestBadge.isVisible = row.isBest
            savings.text = offer.oldPriceKopecks?.let {
                context.getString(R.string.savings_amount, formatMoney(it - offer.priceKopecks))
            }.orEmpty()
            savings.isVisible = offer.oldPriceKopecks != null && maximum == null
            cardPrice.text = offer.cardPriceKopecks?.let { context.getString(R.string.live_card_price, formatMoney(it)) }.orEmpty()
            cardPrice.isVisible = offer.cardPriceKopecks != null
            val scope = if (offer.scope == PriceScope.STORE) offer.address else context.getString(R.string.source_scope_city)
            val fetchedTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(offer.fetchedAt))
            val time = if (offer.cached) context.getString(R.string.live_cached_item, fetchedTime) else fetchedTime
            sourceMeta.text = context.getString(R.string.source_meta, scope, time)
            root.contentDescription = buildList {
                add(context.getString(R.string.offer_accessibility,
                    offer.beer.title, volume.text, offer.store.name, price.text))
                if (row.isBest) add(context.getString(R.string.offer_best_accessibility))
                if (offer.discountPercent > 0) add(context.getString(R.string.offer_discount_accessibility, offer.discountPercent))
                if (savings.isVisible) add(savings.text.toString())
                if (cardPrice.isVisible) add(cardPrice.text.toString())
                add(sourceMeta.text.toString())
                add(context.getString(R.string.offer_open_accessibility))
            }.joinToString(". ")
            root.setOnClickListener { onOfferClick(offer) }
            root.animate().cancel()
            root.alpha = 1f
            root.translationY = 0f
            if (position < 6 && animated.add(offer.id)) {
                root.alpha = 0f
                root.translationY = 8f * root.resources.displayMetrics.density
                root.animate().alpha(1f).translationY(0f).setStartDelay(position * 24L).setDuration(180L).start()
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BeerRow>() {
            override fun areItemsTheSame(oldItem: BeerRow, newItem: BeerRow) = oldItem.offer.id == newItem.offer.id
            override fun areContentsTheSame(oldItem: BeerRow, newItem: BeerRow) = oldItem == newItem
        }
    }
}
