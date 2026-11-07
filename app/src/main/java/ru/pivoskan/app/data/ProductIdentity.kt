package ru.pivoskan.app.data

import ru.pivoskan.app.util.normalize
import java.security.MessageDigest

enum class OrdinaryPriceEvidence { CONFIRMED, UNKNOWN, CONDITIONAL }

data class ProductDescriptor(val sourceProductId: String, val gtin: String? = null,
    val manufacturer: String? = null, val brand: String? = null, val line: String? = null,
    val variant: String? = null, val volumeMl: Int? = null, val packageType: String? = null,
    val units: Int? = null, val abvPermille: Int? = null, val imageUrl: String? = null)

data class ProductRow(val id: String, val title: String, val imageUrl: String?,
    val fresh: List<Offer>, val historical: List<Offer>, val cityCatalog: List<Offer>,
    val minimum: Offer?, val minimumIsHistorical: Boolean)

object ProductIdentity {
    fun validGtin(value: String?): Boolean {
        if (value == null || value.length !in listOf(8, 12, 13, 14) || value.any { it !in '0'..'9' }) return false
        val sum = value.dropLast(1).reversed().mapIndexed { index, c -> (c - '0') * if (index % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10 == value.last() - '0'
    }
    fun key(sourceId: String, product: ProductDescriptor): String {
        // Exact characteristic fingerprints prevent contradictory GTINs and missing attributes
        // from creating transitive matches. Conservative splitting is intentional.
        val attributes = listOf(product.manufacturer, product.brand, product.line, product.variant,
            product.volumeMl?.toString(), product.packageType, product.units?.toString(), product.abvPermille?.toString())
            .map { it?.let(::normalize)?.takeIf(String::isNotBlank) }
        val identity = when {
            validGtin(product.gtin) -> listOf("gtin", product.gtin)
            attributes.all { it != null } -> listOf("attributes")
            else -> listOf("source", sourceId, product.sourceProductId)
        }
        val serialized = (identity + attributes).joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
            .let { bytes ->
                val hex = "0123456789abcdef"
                buildString(64) { bytes.forEach { byte ->
                    val value = byte.toInt() and 255
                    append(hex[value ushr 4]); append(hex[value and 15])
                } }
            }
    }
}
