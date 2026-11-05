package ru.pivoskan.app.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.CityCatalog

class MagnitDiscoveryTest {
    private val city = requireNotNull(CityCatalog.city("balashikha"))
    private fun page(start: Int, count: Int, total: Int, foreign: Boolean = false): String {
        val template = JsonParser.parseString(javaClass.getResource("/remote/magnit-stores.json")!!.readText())
            .asJsonObject.getAsJsonArray("data")[0].asJsonObject
        return JsonObject().apply {
            addProperty("totalCount", total)
            add("data", JsonArray().apply { repeat(count) { i -> add(template.deepCopy().apply {
                getAsJsonObject("externalId").addProperty("storeCode", (start + i).toString())
                addProperty("address", if (foreign) "Московская обл, Химки, ул Ленина, ${i + 1}"
                    else "Московская обл, Балашиха, ул Ленина, ${start + i}")
            }) } })
        }.toString()
    }
    @Test fun discoversAllPagesBeforeCityFiltering() {
        val offsets = mutableListOf<Int>()
        val source = MagnitSource(HttpTransport { request ->
            val offset = JsonParser.parseString(request.body).asJsonObject.getAsJsonObject("pagination").get("offset").asInt
            offsets += offset
            HttpResponse(200, if (offset == 0) page(1, 100, 101, true) else page(101, 1, 101))
        })
        val result = source.discover(city)
        assertEquals(listOf(0, 100), offsets)
        assertEquals(listOf("101"), result.shops.map { it.id })
        assertTrue(result.complete)
        assertEquals(100, result.excludedCount)
    }
    @Test fun repeatedPageIsPartial() {
        var calls = 0
        val result = MagnitSource(HttpTransport { calls++; HttpResponse(200, page(1, 100, 200)) }).discover(city)
        assertFalse(result.complete)
        assertEquals(100, result.shops.size)
        assertEquals(2, calls)
        assertNotNull(result.problem)
    }
    @Test fun changingTotalsAreNotACompleteCity() {
        var calls = 0
        val result = MagnitSource(HttpTransport { HttpResponse(200,
            if (calls++ == 0) page(1, 100, 101) else page(101, 1, 102)) }).discover(city)
        assertFalse(result.complete)
    }
    @Test fun missingTotalCannotClaimComplete() {
        val result = MagnitSource(HttpTransport { HttpResponse(200, "{\"data\":[]}") }).discover(city)
        assertFalse(result.complete)
    }
    @Test fun zeroTotalConfirmsNoShops() {
        val result = MagnitSource(HttpTransport { HttpResponse(200, page(1, 0, 0)) }).discover(city)
        assertTrue(result.complete)
        assertTrue(result.shops.isEmpty())
    }
}
