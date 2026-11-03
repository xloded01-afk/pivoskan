package ru.pivoskan.app.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.Offer
import ru.pivoskan.app.data.PriceScope
import ru.pivoskan.app.data.RemoteShop
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Official, unauthenticated store catalogs. Failure never becomes a successful partial catalog. */
class MagnitSource(
    private val http: HttpTransport,
    private val now: () -> Long = System::currentTimeMillis
) {
    /** A selectable first page (at most 100 stores), not exhaustive citywide coverage. */
    fun shops(city: City): List<RemoteShop> = readSource {
        if (city.id.isBlank() || city.name.isBlank()) fail("Missing requested city")
        val body = JsonObject().apply {
            add("filters", JsonObject().apply {
                addProperty("query", city.name)
                add("storeTypeListV2", JsonArray().apply { add("MM") })
            })
            add("pagination", JsonObject().apply { addProperty("offset", 0); addProperty("size", 100) })
            add("sorting", JsonObject().apply {
                addProperty("sortBy", "SORT_BY_CITY")
                addProperty("sortType", "SORT_TYPE_ASC")
            })
        }
        val data = post("https://magnit.ru/webgate/v1/stores-facade/search/detail", body)
            .array("data") ?: fail("Missing Magnit stores")
        data.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: fail("Invalid Magnit store")
            if (item.flag("isActive") != true ||
                item.text("status")?.let { it != "STATUS_ACTIVE" } == true ||
                item.text("storeTypeV2")?.let { it != "MM" } == true ||
                item.text("storeType")?.let { it != "STORE_TYPE_MM" } == true
            ) return@mapNotNull null
            val address = item.text("address")?.takeIf(::safeText) ?: return@mapNotNull null
            if (!matchesCity(address, city)) return@mapNotNull null
            val id = item.obj("externalId")?.text("storeCode")?.takeIf { it.matches(digits) }
                ?: return@mapNotNull null
            val coordinates = item.obj("coordinates")
            RemoteShop(
                id = id,
                cityId = city.id,
                address = address,
                hours = hours(item),
                latitude = coordinates?.coordinate("latitude", -90.0, 90.0),
                longitude = coordinates?.coordinate("longitude", -180.0, 180.0),
                providerType = "1"
            )
        }.distinctBy { it.id }.take(100)
    }

    fun offers(city: City, shop: RemoteShop): List<Offer> = readSource {
        if (shop.cityId != city.id || city.id.isBlank()) fail("Selected Magnit shop belongs to another city")
        if (!shop.id.matches(digits) || shop.providerType != "1" || !safeText(shop.address)) {
            fail("Invalid selected Magnit shop")
        }
        val fetchedAt = now()
        val offers = linkedMapOf<String, Offer>()
        var offset = 0
        repeat(20) {
            val body = JsonObject().apply {
                addProperty("storeCode", shop.id)
                addProperty("storeType", "1")
                addProperty("catalogType", "1")
                addProperty("includeAdultGoods", true)
                addProperty("term", "пиво")
                add("pagination", JsonObject().apply { addProperty("limit", 50); addProperty("offset", offset) })
                add("sort", JsonObject().apply { addProperty("order", "asc"); addProperty("type", "price") })
            }
            val root = post("https://magnit.ru/webgate/v2/goods/search", body)
            val items = root.array("items") ?: fail("Missing Magnit goods")
            val pagination = root.obj("pagination") ?: fail("Missing Magnit pagination")
            val hasMore = pagination.flag("hasMore") ?: fail("Missing Magnit hasMore")
            // Validate every item before filtering: foreign stock invalidates the whole response.
            val products = items.map { element ->
                val product = element.takeIf { it.isJsonObject }?.asJsonObject ?: fail("Invalid Magnit product")
                if (product.text("storeCode") != shop.id) fail("Magnit response belongs to another shop")
                product
            }
            products.forEach { product ->
                parseOffer(product, city, shop, fetchedAt)?.let { offers[it.id] = it }
            }
            if (!hasMore) return@readSource offers.values.toList()
            val nextOffset = pagination.integer("nextOffset") ?: fail("Missing Magnit nextOffset")
            if (nextOffset <= offset) fail("Magnit pagination did not advance")
            offset = nextOffset
        }
        fail("Magnit goods still have more pages after the 20-page limit")
    }

    private fun parseOffer(product: JsonObject, city: City, shop: RemoteShop, fetchedAt: Long): Offer? {
        val quantity = product.number("quantity") ?: return null
        if (quantity.signum() <= 0) return null
        val title = product.text("name")?.takeIf(::safeText) ?: return null
        if (!isBeer(title)) return null
        val price = product.integer("price")?.takeIf { it > 0 } ?: return null
        val id = product.text("id")?.takeIf { it.matches(digits) } ?: return null
        val seo = product.text("seoCode")?.takeIf { it.matches(slug) } ?: return null
        val promotion = product.obj("promotion")
        val endDate = promotion?.text("endDate")
        val notExpired = endDate == null || (endMillis(endDate)?.let { it >= fetchedAt } == true)
        val oldPrice = promotion?.integer("oldPrice")?.takeIf {
            it > price && notExpired && promotion.flag("isPromotion") != false
        }
        return Offer(
            id = "magnit-${shop.id}-$id",
            beer = ProductNames.beer(title, brand = ""),
            store = requireNotNull(CityCatalog.store("magnit")),
            cityId = city.id,
            priceKopecks = price,
            oldPriceKopecks = oldPrice,
            address = shop.address,
            hours = shop.hours,
            sourceUrl = "https://magnit.ru/product/$id-$seo?shopCode=${shop.id}&shopType=1",
            fetchedAt = fetchedAt,
            scope = PriceScope.STORE,
            shopId = shop.id,
            validUntil = endDate?.takeIf { notExpired }
        )
    }

    private fun post(url: String, body: JsonObject): JsonObject {
        val response = http.execute(HttpRequest(url, method = "POST", body = body.toString(), headers = mapOf(
            "x-magnit-bff" to "1", "Content-Type" to "application/json"
        )))
        if (response.status == 403 || response.status == 429) {
            throw SourceException(blocked = true, message = "Magnit access restricted (${response.status})")
        }
        if (response.status !in 200..299) fail("Magnit HTTP ${response.status}")
        val root = JsonParser.parseString(response.body)
        return root.takeIf { it.isJsonObject }?.asJsonObject ?: fail("Invalid Magnit response")
    }

    private fun matchesCity(address: String, city: City): Boolean {
        val parts = address.split(',').map { normalize(it).trim() }
        val requestedCity = normalize(city.name)
        val cityMatches = parts.any {
            it.replace(Regex("^(?:г\\.?|город)\\s+"), "") == requestedCity
        }
        if (!cityMatches) return false
        if (city.region.isBlank()) return true
        val region = normalizeRegion(city.region)
        return parts.any { normalizeRegion(it) == region }
    }

    private fun normalizeRegion(value: String): String = normalize(value)
        .replace(Regex("\\bобл\\.?($|\\s)"), "область$1")
        .trim()

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("\\s+"), " ").trim()

    private fun isBeer(title: String): Boolean {
        val normalized = normalize(title)
        if (nonBeer.containsMatchIn(normalized)) return false
        return beer.containsMatchIn(normalized)
    }

    private fun hours(store: JsonObject): String {
        val value = store.array("timetableList")?.firstOrNull {
            it.isJsonObject && it.asJsonObject.text("key") == "WORKING_TIMETABLE_TYPE_STORE"
        }?.asJsonObject?.obj("value") ?: return ""
        if (value.flag("is24hOpen") == true) return "круглосуточно"
        val weekly = value.obj("weeklySchedule") ?: return ""
        val days = listOf("monday" to "пн", "tuesday" to "вт", "wednesday" to "ср", "thursday" to "чт",
            "friday" to "пт", "saturday" to "сб", "sunday" to "вс")
        val schedules = days.map { (key, label) ->
            val day = weekly.obj(key)
            val schedule = day?.obj("dailySchedule")
            val opening = schedule?.text("openingTime")?.takeIf { it.matches(time) }
            val closing = schedule?.text("closingTime")?.takeIf { it.matches(time) }
            val text = when {
                day?.flag("isDayOff") == true -> "выходной"
                opening != null && closing != null -> "$opening–$closing"
                else -> null
            }
            label to text
        }
        val daily = schedules.first().second
        if (daily != null && daily != "выходной" && schedules.all { it.second == daily }) return "ежедневно $daily"
        return schedules.mapNotNull { (label, text) -> text?.let { "$label $it" } }.joinToString("; ")
    }

    // java.text works on the project's API 24 devices without java.time desugaring.
    private fun endMillis(value: String): Long? {
        val dateOnly = value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
        val pattern = when {
            dateOnly -> "yyyy-MM-dd"
            value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})")) -> "yyyy-MM-dd'T'HH:mm:ssXXX"
            value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})")) -> "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
            else -> return null
        }
        val parser = SimpleDateFormat(pattern, Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val parsed = parser.parse(value, position) ?: return null
        if (position.index != value.length) return null
        return parsed.time + if (dateOnly) 86_399_999L else 0L
    }

    private fun safeText(value: String) = value.isNotBlank() && value.none {
        it.isISOControl() || it == '<' || it == '>' || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069'
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.flag(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.number(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.let { runCatching { it.asBigDecimal }.getOrNull() }
    private fun JsonObject.integer(key: String): Int? = number(key)?.let { runCatching { it.intValueExact() }.getOrNull() }
    private fun JsonObject.coordinate(key: String, min: Double, max: Double): Double? = number(key)?.toDouble()?.takeIf { it in min..max }

    private inline fun <T> readSource(block: () -> T): T = try {
        block()
    } catch (error: SourceException) {
        throw error
    } catch (error: Exception) {
        fail("Invalid or unavailable Magnit source (${error.javaClass.simpleName})")
    }

    private fun fail(message: String): Nothing = throw SourceException(message = message)

    private companion object {
        val digits = Regex("[0-9]+")
        val slug = Regex("[A-Za-z0-9_-]+")
        val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        val beer = Regex("^(?:(?:светлое|темное|светлый|темный|безалкогольное|безалкогольный|нефильтрованное|нефильтрованный|пшеничное|пшеничный)\\s+)*(?:пиво|пивной\\s+напиток)(?:$|[^а-яa-z])")
        val nonBeer = Regex("(?:^|[^а-яa-z])(?:сухар[а-я]*|чипс[а-я]*|снек[а-я]*|закуск[а-я]*|бокал[а-я]*|кружк[а-я]*|сувенир[а-я]*|набор[а-я]*|открывал[а-я]*)(?:$|[^а-яa-z])")
    }
}
