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
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.RemoteShop

/** Official GET https://dixy.ru/ajax/stores-json.php, an all-stores list.
 * A listed shop is not evidence that it supports pickup, a catalog, or any particular price.
 */
object DixyShops {
    fun parse(json: String, city: City): List<RemoteShop> {
        val knownCity = CityCatalog.city(city.id)
        if (knownCity == null || knownCity.name != city.name || knownCity.region != city.region) {
            fail("unknown or mismatched city")
        }
        if (json.length > MAX_JSON_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            fail("JSON size limit exceeded")
        }
        val shops = linkedMapOf<String, RemoteShop>()
        val seen = mutableMapOf<String, JsonObject>()
        try {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonToken.BEGIN_ARRAY) fail("expected a feature array")
                reader.beginArray()
                var features = 0
                var nodes = 0
                // Also bound unknown fields, not just the fields consumed by the shop mapper.
                fun readValue(depth: Int): JsonElement {
                    if (depth > MAX_DEPTH || ++nodes > MAX_NODES) fail("JSON complexity limit exceeded")
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                if (has(key)) fail("duplicate JSON field")
                                add(key, readValue(depth + 1))
                            }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                if (size() >= MAX_FEATURES) fail("JSON array limit exceeded")
                                add(readValue(depth + 1))
                            }
                            reader.endArray()
                        }
                        JsonToken.STRING -> JsonPrimitive(reader.nextString())
                        JsonToken.NUMBER -> JsonParser.parseString(reader.nextString())
                        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                        else -> fail("invalid JSON value")
                    }
                }
                while (reader.hasNext()) {
                    if (++features > MAX_FEATURES) fail("feature count limit exceeded")
                    val feature = readValue(1).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val properties = feature.obj("properties")
                    // Check conflicts before city/validity filtering so foreign or malformed
                    // duplicates cannot hide an ambiguous ID. Never silently return a prefix.
                    listOfNotNull(feature.text("id"), properties?.text("id"))
                        .filter { ID.matches(it) }.distinct().forEach { id ->
                            val previous = seen.put(id, feature)
                            if (previous != null && previous != feature) fail("conflicting duplicate shop ID")
                        }
                    shop(feature, city)?.let { shops[it.id] = it }
                }
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) fail("trailing JSON data")
            }
        } catch (error: SourceException) {
            throw error
        } catch (_: Exception) {
            fail("invalid stores JSON")
        }
        if (shops.isEmpty()) fail("no valid shops for the requested city")
        return shops.values.toList()
    }

    private fun shop(feature: JsonObject, city: City): RemoteShop? {
        if (feature.text("type") != "Feature") return null
        val id = feature.text("id")?.takeIf { ID.matches(it) } ?: return null
        val properties = feature.obj("properties") ?: return null
        if (properties.text("id") != id) return null
        val address = properties.text("balloonContentBody")?.takeIf { plain(it, MAX_ADDRESS) } ?: return null
        val hours = properties.text("balloonContentFooter")?.takeIf { plain(it, MAX_HOURS) } ?: return null
        if (!CityCatalog.addressMatches(city, address)) return null
        val geometry = feature.obj("geometry") ?: return null
        if (geometry.text("type") != "Point") return null
        val coordinates = geometry["coordinates"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (coordinates.size() != 2) return null
        // This endpoint uses [latitude, longitude], unlike standard GeoJSON's [longitude, latitude].
        val latitude = coordinate(coordinates[0], 90.0) ?: return null
        val longitude = coordinate(coordinates[1], 180.0) ?: return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return RemoteShop(id, city.id, address, hours, latitude, longitude, "dixy")
    }

    private fun coordinate(value: JsonElement, bound: Double): Double? {
        if (!value.isJsonPrimitive) return null
        val primitive = value.asJsonPrimitive
        if (!primitive.isString && !primitive.isNumber) return null
        val text = primitive.asString
        if (text.length > 32 || !DECIMAL.matches(text)) return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() && it in -bound..bound }
    }

    // Saved source fields are plain text. Do not decode entities or strip arbitrary markup:
    // either operation could turn untrusted content into apparent locality evidence.
    private fun plain(value: String, max: Int): Boolean = value.isNotBlank() && value.length <= max &&
        value.none { it.isISOControl() || it == '<' || it == '>' || it in '\u202A'..'\u202E' ||
            it in '\u2066'..'\u2069' || it == '\u200B' || it == '\uFEFF' } &&
        !ENTITY.containsMatchIn(value)

    private fun JsonObject.text(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun fail(message: String): Nothing = throw SourceException(message = "Dixy shops: $message")

    private const val MAX_JSON_BYTES = 8 * 1024 * 1024
    private const val MAX_FEATURES = 10_000
    private const val MAX_DEPTH = 16
    private const val MAX_NODES = 300_000
    private const val MAX_ADDRESS = 1024
    private const val MAX_HOURS = 256
    private val ID = Regex("[1-9][0-9]{0,9}")
    private val DECIMAL = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    private val ENTITY = Regex("&(?:#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
}
