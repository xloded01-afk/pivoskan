package ru.pivoskan.app.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.Offer
import ru.pivoskan.app.data.PriceScope

/** Parses a bounded snapshot of rendered cards; only its selected address establishes locality. */
class DixyCatalogParser(private val now: () -> Long = System::currentTimeMillis) {
    /** City scans replace a whole shop, so silently dropping a damaged eligible card is forbidden. */
    fun parseComplete(json: String, city: City): List<Offer> {
        if (json.length > MAX_JSON_CHARS) fail("catalog size limit exceeded")
        val cards = readCatalog(json)["cards"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?: fail("missing cards")
        val expected = linkedSetOf<String>()
        cards.forEach { value ->
            if (!value.isJsonObject) fail("invalid card")
            val card = value.asJsonObject
            val title = card.text("title") ?: fail("missing title")
            if (title.isBlank() || title.length > MAX_TEXT_CHARS || '<' in title || '>' in title)
                fail("invalid title in complete catalog")
            if (!BEER.containsMatchIn(normalized(title))) return@forEach
            val badges = card["badges"]?.takeIf { it.isJsonArray }?.asJsonArray ?: fail("invalid badges")
            val conditions = badges.map { it.stringValue() ?: fail("invalid badge") }
            if (conditions.any { LOYALTY.containsMatchIn(normalized(it)) }) return@forEach
            expected += card.text("id") ?: fail("missing product id")
        }
        val result = parse(json, city)
        if (result.size != expected.size) fail("eligible cards could not all be validated")
        return result
    }

    fun parse(json: String, city: City): List<Offer> {
        if (json.length > MAX_JSON_CHARS) fail("catalog size limit exceeded")
        val root = readCatalog(json)
        val pageUrl = root.text("pageUrl") ?: fail("missing or invalid pageUrl")
        if (!trustedUrl(pageUrl, "/catalog/")) fail("pageUrl must be an official HTTPS /catalog/ URL")
        val address = root.text("address") ?: fail("missing selected shop address")
        if (address.isBlank() || address.length > MAX_TEXT_CHARS ||
            ADDRESS_PROMPT.containsMatchIn(normalized(address))
        ) fail("selected shop address is empty, invalid or an address-selection placeholder")
        if (city.name.isBlank() || !CityCatalog.addressMatches(city, address)) {
            fail("selected address does not confirm city ${city.name}")
        }
        val cards = root["cards"]?.takeIf { it.isJsonArray }?.asJsonArray
            ?: fail("missing or invalid cards array")
        if (cards.size() > MAX_CARDS) fail("card count limit exceeded")

        val shopId = "dixy-${addressHash(address)}"
        val fetchedAt = now()
        val offers = linkedMapOf<String, Offer>()
        val conflicts = mutableSetOf<String>()
        cards.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val card = element.asJsonObject
            val productId = card.text("id")?.takeIf { it.length <= MAX_ID_CHARS && PRODUCT_ID.matches(it) }
                ?: return@forEach
            if (productId in conflicts) return@forEach
            val offer = parseCard(card, productId, city, address, shopId, fetchedAt) ?: return@forEach
            val previous = offers[productId]
            if (previous == null) {
                offers[productId] = offer
            } else if (previous != offer) {
                // Never choose a winning price or allow a later duplicate to resurrect a conflict.
                offers.remove(productId)
                conflicts += productId
            }
        }
        if (offers.isEmpty()) fail("no valid beer cards for the selected shop")
        return offers.values.toList()
    }

    private fun parseCard(
        card: JsonObject, productId: String, city: City, address: String, shopId: String, fetchedAt: Long
    ): Offer? {
        val title = card.text("title")?.takeIf {
            it.length <= MAX_TEXT_CHARS && '<' !in it && '>' !in it && BEER.containsMatchIn(normalized(it))
        } ?: return null
        val url = card.text("url")?.takeIf { trustedUrl(it, "/product/") } ?: return null
        val badges = card["badges"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (badges.size() > MAX_BADGES) return null
        for (element in badges) {
            val badge = element.stringValue()?.takeIf { it.length <= MAX_TEXT_CHARS } ?: return null
            if (LOYALTY.containsMatchIn(normalized(badge))) return null
        }
        val price = money(card["priceRub"]) ?: return null
        // An absent crossed-price DOM node is rendered as an empty string by the extractor.
        val oldValue = card["oldPriceRub"]?.takeUnless { it.isJsonNull || it.stringValue()?.isBlank() == true }
        val oldPrice = if (oldValue == null) null else money(oldValue) ?: return null
        return Offer(
            id = "${city.id}-$shopId-$productId",
            beer = ProductNames.beer(title),
            store = requireNotNull(CityCatalog.store("dixy")),
            cityId = city.id,
            priceKopecks = price,
            oldPriceKopecks = oldPrice?.takeIf { it > price },
            address = address,
            sourceUrl = url,
            fetchedAt = fetchedAt,
            scope = PriceScope.STORE,
            shopId = shopId,
            cached = false
        )
    }

    private fun money(element: JsonElement?): Int? {
        if (element == null || !element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        if (!primitive.isString && !primitive.isNumber) return null
        val text = primitive.asString
        // Restrict the lexical precision too: 1.000 must not silently become 100 kopecks.
        if (text.length > MAX_MONEY_CHARS || !DECIMAL.matches(text)) return null
        return try {
            BigDecimal(text.replace(',', '.')).movePointRight(2).intValueExact().takeIf { it > 0 }
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun trustedUrl(value: String, route: String): Boolean {
        if (value.length > MAX_URL_CHARS) return false
        val uri = try { URI(value) } catch (_: Exception) { return false }
        val path = uri.path ?: return false
        val rawPath = uri.rawPath ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("dixy.ru", ignoreCase = true) && uri.rawUserInfo == null &&
            uri.port in listOf(-1, 443) && rawPath.startsWith(route) && path.startsWith(route) &&
            (route != "/product/" || path.removePrefix(route).trim('/').isNotBlank()) &&
            path.split('/').none { it == "." || it == ".." } &&
            '\\' !in path && !ENCODED_SEPARATOR.containsMatchIn(rawPath)
    }

    private fun addressHash(address: String): String = MessageDigest.getInstance("SHA-256")
        .digest(normalized(address).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun JsonObject.text(name: String): String? = get(name)?.stringValue()

    private fun JsonElement.stringValue(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun normalized(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ё', 'е').replace(WHITESPACE, " ").trim()

    private fun readCatalog(json: String): JsonObject = try {
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            val root = readValue(reader, 0)
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.isJsonObject) {
                fail("expected one catalog JSON object")
            }
            root.asJsonObject
        }
    } catch (error: SourceException) {
        throw error
    } catch (_: Exception) {
        fail("invalid catalog JSON")
    }

    // JsonParser's general tree reader is lenient and recursively unbounded. Keep strict JSON
    // syntax and cap depth before descending, including unknown fields in the parent snapshot.
    private fun readValue(reader: JsonReader, depth: Int): JsonElement {
        if (depth > MAX_JSON_DEPTH) fail("JSON nesting limit exceeded")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (has(name)) fail("duplicate JSON field $name")
                    add(name, readValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (size() >= MAX_CARDS) fail("array/card count limit exceeded")
                    add(readValue(reader, depth + 1))
                }
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            // The strict streaming reader already validated this token. Preserve its spelling
            // rather than converting through Double or losing decimal precision/exponent data.
            JsonToken.NUMBER -> JsonParser.parseString(reader.nextString())
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> fail("invalid catalog JSON value")
        }
    }

    private fun fail(message: String): Nothing = throw SourceException(message = "Dixy: $message")

    private companion object {
        const val MAX_JSON_CHARS = 1_000_000
        const val MAX_JSON_DEPTH = 16
        const val MAX_CARDS = 500
        const val MAX_BADGES = 32
        const val MAX_TEXT_CHARS = 512
        const val MAX_ID_CHARS = 128
        const val MAX_URL_CHARS = 2048
        const val MAX_MONEY_CHARS = 32
        val WHITESPACE = Regex("[\\s\\p{Z}]+")
        val PRODUCT_ID = Regex("[\\p{L}\\p{M}\\p{N}_-]+")
        val DECIMAL = Regex("[0-9]+(?:[.,][0-9]{1,2})?")
        val BEER = Regex("^(?:пиво|пивной напиток)(?![\\p{L}\\p{M}\\p{N}_])")
        val LOYALTY = Regex("по карт|купон|персонал")
        val ENCODED_SEPARATOR = Regex("%(?:2f|5c|25)", RegexOption.IGNORE_CASE)
        val ADDRESS_PROMPT = Regex(
            "(?:выбер[\\p{L}]*|выбрат[\\p{L}]*|укаж[\\p{L}]*|указат[\\p{L}]*) " +
                "(?:[\\p{L}]+ ){0,5}(?:адрес|магазин|способ)|(?:адрес|магазин) не (?:выбран|указан)"
        )
    }
}
