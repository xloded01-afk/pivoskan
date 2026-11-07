package ru.pivoskan.app.data

import com.google.gson.JsonObject

/** Gson does not invoke Kotlin defaults when restoring old records. No price proof is inferred. */
internal object CachedOfferDefaults {
    fun apply(offer: JsonObject) {
        val evidence = offer.get("priceEvidence")
        if (evidence == null || !evidence.isJsonPrimitive || !evidence.asJsonPrimitive.isString ||
            OrdinaryPriceEvidence.entries.none { it.name == evidence.asString }) {
            offer.addProperty("priceEvidence", OrdinaryPriceEvidence.UNKNOWN.name)
        }
        fun emptyText(objectValue: JsonObject, name: String) {
            if (objectValue.get(name)?.takeUnless { it.isJsonNull } == null) objectValue.addProperty(name, "")
        }
        emptyText(offer, "hours")
        offer.get("beer")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
            emptyText(it, "brand")
            emptyText(it, "packageName")
        }
    }
}
