package ru.pivoskan.app.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.RemoteShop

class DixyShopsTest {
    private val city = requireNotNull(CityCatalog.city("balashikha"))

    @Test fun `official feature preserves source identity address hours and latitude first coordinates`() {
        assertEquals(listOf(RemoteShop("350", "balashikha", ADDRESS, "08 - 23",
            55.825496, 37.923316, "dixy")), parse(feature()))
    }

    @Test fun `all stores need no pickup filter and identical duplicates collapse in source order`() {
        val other = feature("351")
        assertEquals(listOf("350", "351"), parse(feature(), other, feature()).map { it.id })
    }

    @Test fun `only addresses confirming the requested city and region are returned`() {
        val wrongAddresses = listOf(
            "Московская обл, Реутов г, Гагарина ул, 30",
            "Балашиха г, Гагарина мкр, 30", "Другая обл, Балашиха г, 30",
            "Московская обл, Балашиханский район, 30",
            "Московская обл, Новобалашиха г, 30"
        )
        wrongAddresses.forEach { address ->
            val wrong = feature("351").apply { props().addProperty("balloonContentBody", address) }
            assertEquals(listOf("350"), parse(wrong, feature()).map { it.id })
            sourceError { parse(wrong) }
        }
    }

    @Test fun `unknown or mismatched city metadata cannot label source shops`() {
        listOf(City("unknown", "Балашиха", "Московская область"),
            city.copy(name = "Реутов"), city.copy(region = ""), city.copy(id = ""),
            city.copy(name = "")).forEach { requested ->
            sourceError { DixyShops.parse(array(feature()), requested) }
        }
    }

    @Test fun `invalid IDs mismatched IDs and feature types are excluded`() {
        val invalid = listOf("", "0", "-1", "0350", "350x", "1.0", "999999999999999999999")
            .map { id -> feature().apply { addProperty("id", id); props().addProperty("id", id) } } +
            listOf(feature().apply { props().addProperty("id", "351") },
                feature().apply { remove("id") }, feature().apply { props().remove("id") },
                feature().apply { addProperty("id", 350) },
                feature().apply { addProperty("type", "Unknown") },
                feature().apply { getAsJsonObject("geometry").addProperty("type", "Polygon") })
        invalid.forEach { row -> sourceError { parse(row) } }
    }

    @Test fun `coordinates must be exactly two finite nondegenerate in-range values`() {
        listOf("[]", "[55.8]", "[55.8,37.9,1]", "[91,37]", "[-91,37]",
            "[55,181]", "[55,-181]", "[0,0]", "[null,37]", "[true,37]",
            "[{},37]", "[\"NaN\",37]", "[55,\"Infinity\"]", "[\"55,8\",37]",
            "[\"\",37]").forEach { coords ->
            val bad = feature("351").apply {
                getAsJsonObject("geometry").add("coordinates", JsonParser.parseString(coords))
            }
            assertEquals(listOf("350"), parse(bad, feature()).map { it.id })
            sourceError { parse(bad) }
        }
        val numeric = feature().apply {
            getAsJsonObject("geometry").add("coordinates", JsonParser.parseString("[55.825496,37.923316]"))
        }
        assertEquals(55.825496, parse(numeric).single().latitude!!, 0.0)
    }

    @Test fun `address and hours must be bounded plain source text without markup or entities`() {
        listOf("balloonContentBody", "balloonContentFooter").forEach { key ->
            listOf("", " ", "<b>$ADDRESS</b>", "<script>bad</script>$ADDRESS",
                "$ADDRESS &lt;b&gt;", "$ADDRESS &#60;b&#62;", "$ADDRESS\u0000",
                "$ADDRESS\u202e", "x".repeat(1025)).forEach { value ->
                sourceError { parse(feature().apply { props().addProperty(key, value) }) }
            }
            sourceError { parse(feature().apply { props().remove(key) }) }
            sourceError { parse(feature().apply { props().addProperty(key, true) }) }
        }
    }

    @Test fun `conflicting duplicate identities fail even across cities and irrespective of order`() {
        val conflicts = listOf(
            feature().apply { props().addProperty("balloonContentBody", "Московская обл, Реутов г, 30") },
            feature().apply { props().addProperty("balloonContentFooter", "09 - 22") },
            feature().apply { props().addProperty("id", "351") },
            feature().apply { getAsJsonObject("geometry").add("coordinates", JsonParser.parseString("[56,38]")) }
        )
        conflicts.forEach { conflict ->
            sourceError { parse(feature(), conflict, feature()) }
            sourceError { parse(conflict, feature(), conflict) }
        }
    }

    @Test fun `malformed unknown and empty responses are source errors`() {
        listOf("", "null", "{}", "[]", "[null,{},17]", "{", "not JSON",
            array(feature()) + " garbage", array(feature()) + array(feature()),
            "/* comment */" + array(feature()), array(feature()).replace("\"type\":", "type:"),
            array(feature()).replace("\"type\":", "'type':"),
            array(feature()).replace("\"id\":\"350\"", "\"id\":\"351\",\"id\":\"350\""))
            .forEach { json -> sourceError { DixyShops.parse(json, city) } }
    }

    @Test fun `eight MiB byte limit rejects oversized input but accepts evidence sized response`() {
        val row = feature().apply { addProperty("unused", "x".repeat(1_600_000)) }
        assertEquals("350", parse(row).single().id)
        sourceError { DixyShops.parse(" ".repeat(8 * 1024 * 1024 + 1), city) }
        row.addProperty("unused", "я".repeat(4_200_000))
        sourceError { parse(row) }
    }

    @Test fun `feature count and unknown nesting are bounded without returning a prefix`() {
        val many = JsonArray().apply { repeat(10_001) { add(feature()) } }
        sourceError { DixyShops.parse(many.toString(), city) }
        val nested = "[".repeat(1000) + "0" + "]".repeat(1000)
        val row = feature().toString().dropLast(1) + ",\"unused\":$nested}"
        sourceError { DixyShops.parse("[$row]", city) }
    }

    private fun parse(vararg rows: JsonElement) = DixyShops.parse(array(*rows), city)
    private fun array(vararg rows: JsonElement) = JsonArray().apply { rows.forEach { add(it) } }.toString()
    private fun JsonObject.props() = getAsJsonObject("properties")
    private fun feature(id: String = "350") = JsonParser.parseString(FEATURE).asJsonObject.apply {
        addProperty("id", id); props().addProperty("id", id)
    }
    private fun sourceError(block: () -> Unit) {
        try { block() } catch (error: SourceException) {
            assertFalse(error.blocked)
            assertTrue(error.message.orEmpty().isNotBlank())
            return
        }
        fail("Expected SourceException")
    }

    private companion object {
        // Transcribed from .superpowers/live-prices/dixy-stores.json, feature 350,
        // cross-checked with dixy-balashikha-evidence.json. Not evidence of catalog availability.
        const val ADDRESS = "143900, Московская обл, Балашиха г, Гагарина мкр, 30"
        const val FEATURE = """{"type":"Feature","id":"350",
            "geometry":{"type":"Point","coordinates":["55.825496","37.923316"]},
            "properties":{"id":"350","balloonContentHeader":"ДИКСИ 50099",
            "balloonContentBody":"143900, Московская обл, Балашиха г, Гагарина мкр, 30",
            "balloonContentFooter":"08 - 23"}}"""
    }
}
