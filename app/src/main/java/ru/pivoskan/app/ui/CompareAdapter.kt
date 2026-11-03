package ru.pivoskan.app.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ru.pivoskan.app.R
import ru.pivoskan.app.data.Offer
import ru.pivoskan.app.data.PriceScope
import ru.pivoskan.app.databinding.ItemCompareBinding
import ru.pivoskan.app.util.formatMoney

/** A tiny, immutable comparison list; ordering and highlighting are independent of recycling. */
class CompareAdapter(
    offers: List<Offer>,
    private val currentOfferId: String,
    private val onOfferClick: (Offer) -> Unit
) : RecyclerView.Adapter<CompareAdapter.ViewHolder>() {
    private val items = offers.sortedWith(compareBy<Offer> { it.priceKopecks }.thenBy { it.store.id }.thenBy { it.id })
    private val cheapestPrice = items.minOfOrNull { it.priceKopecks }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemCompareBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemCompareBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(offer: Offer) = with(binding) {
            val context = root.context
            val current = offer.id == currentOfferId
            val maximumPrice = offer.maxPriceKopecks?.takeIf { it > offer.priceKopecks }
            val cheapest = offer.priceKopecks == cheapestPrice && maximumPrice == null
            val priceText = if (maximumPrice != null) {
                context.getString(R.string.details_price_range, formatMoney(offer.priceKopecks), formatMoney(maximumPrice))
            } else formatMoney(offer.priceKopecks)
            storeName.text = offer.store.name
            price.text = priceText
            scope.text = if (offer.scope == PriceScope.CITY_CATALOG) {
                context.getString(R.string.details_compare_city_catalog)
            } else if (offer.address.isNotBlank()) {
                context.getString(R.string.details_compare_store_address, offer.address)
            } else context.getString(R.string.details_address_unknown)
            if (offer.cached) scope.text = context.getString(R.string.live_cached_item, scope.text)
            cardPrice.isVisible = offer.cardPriceKopecks != null
            cardPrice.text = offer.cardPriceKopecks?.let {
                context.getString(R.string.details_card_price, formatMoney(it))
            }.orEmpty()
            storeDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(offer.store.color)
            }
            root.setCardBackgroundColor(ContextCompat.getColor(
                context, if (current) R.color.gold_container else R.color.surface
            ))
            root.strokeColor = ContextCompat.getColor(
                context, if (current) R.color.gold else R.color.outline
            )
            val textColor = ContextCompat.getColor(
                context, if (current) R.color.on_gold_container else R.color.text_primary
            )
            storeName.setTextColor(textColor)
            price.setTextColor(textColor)
            scope.setTextColor(textColor)
            cardPrice.setTextColor(textColor)
            currentLabel.isVisible = current
            bestLabel.isVisible = cheapest
            // The selected row remains focusable and has an explicit state beyond its color.
            root.isFocusable = true
            root.isSelected = current
            val description = context.getString(
                if (current) R.string.details_compare_current else R.string.details_compare_open,
                offer.store.name, priceText, scope.text, cardPrice.text
            )
            root.contentDescription = if (cheapest) {
                context.getString(R.string.details_compare_best_accessibility, description)
            } else description
            root.setOnClickListener(if (current) null else android.view.View.OnClickListener {
                onOfferClick(offer)
            })
            root.isClickable = !current
        }
    }
}
