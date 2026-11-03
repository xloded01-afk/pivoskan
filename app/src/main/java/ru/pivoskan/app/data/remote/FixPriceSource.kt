package ru.pivoskan.app.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.Offer
import ru.pivoskan.app.data.PriceScope

/** Public city catalog only: a catalog price is never attributed to a physical shop. */
class FixPriceSource(
    private val http: HttpTransport,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun offers(city: City): List<Offer> = try {
        loadOffers(city)
    } catch (error: SourceException) {
        throw error
    } catch (error: Exception) {
        throw SourceException(message = "Fix Price: ${error.message ?: "invalid response"}")
    }

    private fun loadOffers(city: City): List<Offer> {
        val remoteCity = findCity(city)
        val fetchedAt = now()
        val result = mutableListOf<Offer>()
        val seenIds = mutableSetOf<String>()
        var received = 0
        var expected: Int? = null

        for (page in 1..MAX_PAGES) {
            val response = request(HttpRequest(
                url = "$API/product/in/$CATEGORY?page=$page&limit=$PAGE_SIZE&sort=sold",
                method = "POST",
                body = CATEGORY_REQUEST,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "x-language" to "ru",
                    "x-city" to remoteCity
                )
            ))
            if (response.header("X-City") != remoteCity) {
                fail("catalog response does not confirm requested city $remoteCity")
            }
            response.header("X-Count")?.let { value ->
                val count = value.takeIf { COUNT.matches(it) }?.toIntOrNull()
                    ?: fail("invalid X-Count")
                if (expected != null && expected != count) fail("catalog count changed between pages")
                expected = count
            }
            val products = array(response.body)
            if (products.size() > PAGE_SIZE) fail("catalog ignored page size")
            received += products.size()
            products.forEach { element ->
                parseOffer(objectValue(element), city, fetchedAt)?.let { offer ->
                    if (!seenIds.add(offer.id)) fail("catalog repeated a product between pages")
                    result += offer
                }
            }

            val total = expected
            if (total != null) {
                if (received > total) fail("catalog exceeds advertised count")
                if (received == total) return result
                if (products.size() < PAGE_SIZE) fail("catalog ended before advertised count")
            } else if (products.size() < PAGE_SIZE) {
                return result
            }
        }
        fail("catalog exceeds $MAX_PAGES pages; partial results discarded")
    }

    private fun findCity(city: City): String {
        val title = normalized(city.name)
        if (title.isEmpty()) fail("city name is empty")
        val region = normalized(city.region)
        val query = URLEncoder.encode(city.name.trim(), "UTF-8")
        val response = request(HttpRequest(
            url = "$API/location/city?titlePart=$query",
            headers = mapOf("Accept" to "application/json", "x-language" to "ru")
        ))
        val matches = array(response.body).map(::objectValue).filter {
            normalized(it.text("title") ?: "") == title &&
                (region.isEmpty() || normalized(it.text("regionTitle") ?: "") == region)
        }
        if (matches.size != 1) fail("city is unsupported or ambiguous: ${city.name}, ${city.region}")
        val id = integer(matches.single()["id"], "city id")
        if (id <= 0) fail("invalid city id")
        return id.toString()
    }

    private fun parseOffer(product: JsonObject, city: City, fetchedAt: Long): Offer? {
        if (product.flag("active") != true || product.flag("forbidden") != false) return null
        val stock = product["inStock"]?.takeUnless { it.isJsonNull } ?: return null
        if (integer(stock, "inStock") <= 0) return null
        val title = product.text("title") ?: fail("missing product title")
        if (!BEER.containsMatchIn(normalized(title))) return null

        val sku = product.text("sku")?.takeIf { it.isNotBlank() } ?: fail("missing product sku")
        val ordinary = money(product["price"], "price")
        val minimum = product.optionalMoney("minPrice") ?: ordinary
        val maximum = product.optionalMoney("maxPrice") ?: ordinary
        if (minimum > maximum || ordinary !in minimum..maximum) fail("invalid ordinary price range")
        val special = product["specialPrice"]?.takeUnless { it.isJsonNull }?.let(::objectValue)
        val card = cardPromotion(special, fetchedAt)
        val brand = product["brand"]?.takeUnless { it.isJsonNull }?.let(::objectValue)?.text("title") ?: ""
        val sourceUrl = productUrl(product.text("url") ?: fail("missing product URL"))

        return Offer(
            id = "${city.id}-fixprice-$sku",
            beer = ProductNames.beer(title, brand),
            store = requireNotNull(CityCatalog.store("fixprice")),
            cityId = city.id,
            priceKopecks = minimum,
            oldPriceKopecks = null,
            address = "",
            hours = "",
            sourceUrl = sourceUrl,
            fetchedAt = fetchedAt,
            scope = PriceScope.CITY_CATALOG,
            shopId = null,
            cardPriceKopecks = card?.priceKopecks,
            validUntil = card?.validUntil,
            maxPriceKopecks = maximum.takeIf { it > minimum }
        )
    }

    private data class CardPromotion(val priceKopecks: Int, val validUntil: String?)

    private fun cardPromotion(special: JsonObject?, at: Long): CardPromotion? {
        if (special == null) return null
        val price = special["price"]?.takeUnless {
            it.isJsonNull || (it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isEmpty())
        } ?: return null
        val fromText = special.text("active_from")?.takeIf { it.isNotBlank() }
        val toText = special.text("active_to")?.takeIf { it.isNotBlank() }
        val from = fromText?.let { promotionDate(it) ?: return null }
        val to = toText?.let { promotionDate(it) ?: return null }
        // Year 2999 is the observed backend sentinel, not a finite expiry.
        val finiteTo = to?.takeUnless { toText?.startsWith("2999-") == true }
        if ((from != null && at < from) || (finiteTo != null && at >= finiteTo)) return null
        return CardPromotion(money(price, "specialPrice.price"), toText.takeIf { finiteTo != null })
    }

    private fun promotionDate(value: String): Long? {
        if (!PROMOTION_DATE.matches(value)) return null
        // Fix Price supplies no offset; interpret these Russian catalog dates in Moscow time.
        // Each parse owns its formatter because SimpleDateFormat is mutable and not thread-safe.
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("Europe/Moscow")
        }
        val position = ParsePosition(0)
        val parsed = parser.parse(value, position) ?: return null
        if (position.index != value.length || parser.format(parsed) != value) return null
        return parsed.time
    }

    private fun request(request: HttpRequest): HttpResponse {
        val response = http.execute(request)
        if (response.status == 403 || response.status == 429) {
            throw SourceException(blocked = true, message = "Fix Price access restricted: HTTP ${response.status}")
        }
        if (response.status != 200) fail("HTTP ${response.status}")
        return response
    }

    private fun productUrl(value: String): String {
        if (value.isBlank()) fail("empty product URL")
        val uri = PRODUCT_BASE.resolve(value).normalize()
        if (uri.scheme != "https" || !"fix-price.com".equals(uri.host, ignoreCase = true) ||
            uri.userInfo != null || uri.port !in listOf(-1, 443) ||
            uri.path?.startsWith("/catalog/") != true ||
            uri.path.split('/').any { it == "." || it == ".." }
        ) {
            fail("product URL is not an official HTTPS catalog URL")
        }
        return uri.toASCIIString()
    }

    private fun money(value: JsonElement?, name: String): Int {
        val amount = decimal(value, name).movePointRight(2).intValueExact()
        if (amount <= 0) fail("nonpositive $name")
        return amount
    }

    private fun integer(value: JsonElement?, name: String): Int = decimal(value, name).intValueExact()

    private fun decimal(value: JsonElement?, name: String): BigDecimal {
        if (value == null || !value.isJsonPrimitive) fail("missing or invalid $name")
        val primitive = value.asJsonPrimitive
        if (!primitive.isString && !primitive.isNumber) fail("invalid $name")
        return BigDecimal(primitive.asString)
    }

    private fun JsonObject.optionalMoney(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }
        ?.let { money(it, name) }

    private fun JsonObject.text(name: String): String? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) fail("invalid $name")
        return value.asString
    }

    private fun JsonObject.flag(name: String): Boolean? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) fail("invalid $name")
        return value.asBoolean
    }

    private fun array(body: String): JsonArray {
        val value = JsonParser.parseString(body)
        if (!value.isJsonArray) fail("expected a JSON array")
        return value.asJsonArray
    }

    private fun objectValue(value: JsonElement): JsonObject {
        if (!value.isJsonObject) fail("expected a JSON object")
        return value.asJsonObject
    }

    private fun normalized(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ё', 'е').replace(WHITESPACE, " ").trim()

    private fun fail(message: String): Nothing = throw SourceException(message = "Fix Price: $message")

    private companion object {
        const val API = "https://api.fix-price.com/buyer/v1"
        const val CATEGORY = "produkty-i-napitki/alkogolnaya-produktsiya"
        const val PAGE_SIZE = 24
        const val MAX_PAGES = 20
        val PRODUCT_BASE: URI = URI("https://fix-price.com/catalog/")
        val COUNT = Regex("[0-9]+")
        val PROMOTION_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")
        val WHITESPACE = Regex("[\\s\\u00a0]+")
        val BEER = Regex(
            "^(?:(?:(?:светлое|темное|пшеничное|безалкогольное|нефильтрованное|" +
                "фильтрованное|пастеризованное|непастеризованное|крафтовое|крепкое) )*пиво\\b|" +
                "пивной напиток\\b|напиток пивной\\b)"
        )
        val CATEGORY_REQUEST: String = """{"category":"$CATEGORY","brand":[],"badge":[],"price":[],"isDividedPrice":false,"isSpecialPrice":false}"""
    }
}
