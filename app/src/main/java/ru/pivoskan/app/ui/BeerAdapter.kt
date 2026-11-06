package ru.pivoskan.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.pivoskan.app.data.*
import ru.pivoskan.app.databinding.ItemBeerBinding
import ru.pivoskan.app.util.formatMoney
import ru.pivoskan.app.util.formatVolume
import java.text.DateFormat
import java.util.Date

class BeerAdapter(private val images: ProductImageLoader, private val onOfferClick: (Offer) -> Unit) :
    ListAdapter<ProductRow, BeerAdapter.Holder>(DIFF) {
    val expanded = mutableSetOf<String>()
    private val ids = mutableMapOf<String, Long>()
    init { setHasStableIds(true); stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    override fun getItemId(position: Int) = ids.getOrPut(getItem(position).id) { ids.size.toLong() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemBeerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    override fun onViewRecycled(holder: Holder) { holder.clear(); super.onViewRecycled(holder) }
    inner class Holder(private val binding: ItemBeerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun clear() = images.clear(binding.productImage)
        fun bind(row: ProductRow): Unit = with(binding) {
            val entries = row.fresh + row.historical + row.cityCatalog
            val representative = row.minimum ?: entries.first()
            storeName.text = entries.map { it.store.name }.distinct().joinToString(" · ")
            storeDot.isVisible = false
            beerName.text = row.title
            volume.text = representative.beer.volumeMl.takeIf { it > 0 }?.let(::formatVolume).orEmpty()
            volume.isVisible = volume.text.isNotEmpty()
            price.text = row.minimum?.let { (if (row.minimumIsHistorical) "Ранее: " else "") + formatMoney(it.priceKopecks) }
                ?: row.cityCatalog.firstOrNull()?.let { "Каталог: от ${formatMoney(it.priceKopecks)}" }
                ?: "Обычная цена не подтверждена"
            sourceMeta.text = when {
                row.minimumIsHistorical -> "Сохранённая цена · ${date(row.minimum!!.fetchedAt)}"
                row.minimum != null -> "Минимум среди проверенных · без карты"
                row.cityCatalog.isNotEmpty() -> "Каталог города — цена по адресу не подтверждена"
                else -> "Цены доступны в списке адресов"
            }
            listOf(oldPrice, savings, cardPrice, discountBadge, bestBadge).forEach { it.isVisible = false }
            images.load(row.imageUrl, productImage)
            productImage.isVisible = row.imageUrl != null
            productImage.contentDescription = "Фото: ${row.title}"
            val open = row.id in expanded
            expandAddresses.text = if (open) "Скрыть цены по адресам" else "Цены по адресам (${entries.size})"
            ViewCompat.setStateDescription(expandAddresses, if (open) "Развёрнуто" else "Свёрнуто")
            val toggle = {
                if (!expanded.add(row.id)) expanded.remove(row.id)
                bind(row)
            }
            expandAddresses.setOnClickListener { toggle() }
            root.setOnClickListener { toggle() }
            addresses.removeAllViews(); addresses.isVisible = open
            if (open) entries.forEach { offer ->
                val button = TextView(root.context).apply {
                    val dp = resources.displayMetrics.density
                    minHeight = (48 * dp).toInt()
                    setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                    setTextColor(androidx.core.content.ContextCompat.getColor(context, ru.pivoskan.app.R.color.text_primary))
                    textSize = 14f
                    val status = if (offer.priceEvidence != OrdinaryPriceEvidence.CONFIRMED) " · обычная цена не подтверждена"
                        else if (offer.cached) " · ранее" else ""
                    val address = if (offer.scope == PriceScope.CITY_CATALOG) "Каталог города, адресная цена не подтверждена" else offer.address
                    text = "${offer.store.name} · ${formatMoney(offer.priceKopecks)}$status\n$address\n${date(offer.fetchedAt)}"
                    isClickable = true; isFocusable = true
                    setOnClickListener { onOfferClick(offer) }
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * dp).toInt() }
                }
                addresses.addView(button)
            }
        }
    }
    private fun date(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ProductRow>() {
            override fun areItemsTheSame(oldItem: ProductRow, newItem: ProductRow) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ProductRow, newItem: ProductRow) = oldItem == newItem
        }
    }
}
