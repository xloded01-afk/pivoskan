package ru.pivoskan.app.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.PriceScope

class FixPriceSourceTest {
    private val city = City("balashikha", "Балашиха", "Московская область")

    @Test
    fun `saved live products preserve kopecks card price and city scope`() {
        val http = FixtureHttp()
        val offers = FixPriceSource(http) { 123456L }.offers(city)

        assertEquals(2, offers.size)
        val nonAlcoholic = offers.single { it.beer.title.contains("безалкогольное") }
        val pilsner = offers.single { it.beer.title.contains("Пильзенское") }
        assertEquals(4950, nonAlcoholic.priceKopecks)
        assertNull(nonAlcoholic.cardPriceKopecks)
        assertEquals(8700, pilsner.priceKopecks)
        assertEquals(8400, pilsner.cardPriceKopecks)
        assertNull(pilsner.oldPriceKopecks)
        assertEquals(0, pilsner.discountPercent)
        assertNull(pilsner.validUntil) // The live 2999 expiry is a placeholder.
        assertEquals(
            "https://fix-price.com/catalog/spets-tsena-po-karte/p-1430168-svetloe-pivo-pilzenskoe-hamovniki-48-045-l",
            pilsner.sourceUrl
        )
        offers.forEach {
            assertEquals("fixprice", it.store.id)
            assertEquals("balashikha", it.cityId)
            assertEquals(PriceScope.CITY_CATALOG, it.scope)
            assertEquals("", it.address)
            assertEquals("", it.hours)
            assertNull(it.shopId)
            assertEquals(123456L, it.fetchedAt)
        }
        assertEquals(2, offers.map { it.id }.distinct().size)
        val lookup = http.requests.first()
        assertEquals("GET", lookup.method)
        assertEquals(
            "titlePart=Балашиха",
            URLDecoder.decode(URI(lookup.url).rawQuery, "UTF-8")
        )
        val request = http.requests.last()
        assertEquals("111", request.headers["x-city"])
        assertFalse(request.headers.keys.any { it.equals("x-pfm", true) })
        val body = JsonParser.parseString(request.body).asJsonObject
        assertEquals("produkty-i-napitki/alkogolnaya-produktsiya", body["category"].asString)
        assertEquals(0, body["brand"].asJsonArray.size())
        assertEquals(0, body["badge"].asJsonArray.size())
        assertEquals(0, body["price"].asJsonArray.size())
        assertFalse(body["isDividedPrice"].asBoolean)
        assertFalse(body["isSpecialPrice"].asBoolean)
    }

    @Test
    fun `exact normalized title and region select the matching remote city`() {
        val cities = JsonParser.parseString(fixture("fix-city-balashikha.json")).asJsonArray
        val wrongRegion = cities[0].deepCopy().asJsonObject.apply {
            addProperty("id", 999)
            addProperty("regionTitle", "Другая область")
        }
        cities.add(wrongRegion)
        val http = FixtureHttp(cityBody = cities.toString())

        val offers = FixPriceSource(http).offers(city.copy(name = "  БАЛАШИХА  ", region = " московская\u00a0ОБЛАСТЬ "))

        assertEquals(2, offers.size)
        assertEquals("111", http.requests.last().headers["x-city"])
    }

    @Test
    fun `city normalization equates yo and e`() {
        val cities = JsonParser.parseString(fixture("fix-city-balashikha.json")).asJsonArray
        cities[0].asJsonObject.addProperty("title", "Королев")
        val http = FixtureHttp(cityBody = cities.toString())

        assertEquals(2, FixPriceSource(http).offers(City("korolev", "Королёв", "Московская область")).size)
    }

    @Test
    fun `unsupported city region mismatch and partial title never fall back`() {
        val wrongCities = listOf(
            "[]",
            fixture("fix-city-balashikha.json").replace("Московская область", "Другая область"),
            fixture("fix-city-balashikha.json").replace("Балашиха", "Балашиха-2")
        )
        wrongCities.forEach { body ->
            val http = FixtureHttp(cityBody = body)
            assertThrows(SourceException::class.java) { FixPriceSource(http).offers(city) }
            assertEquals(1, http.requests.size)
        }
    }

    @Test
    fun `ambiguous city lookup is rejected rather than choosing first`() {
        val cities = JsonParser.parseString(fixture("fix-city-balashikha.json")).asJsonArray
        cities.add(cities[0].deepCopy().asJsonObject.apply { addProperty("id", 222) })
        val http = FixtureHttp(cityBody = cities.toString())

        assertThrows(SourceException::class.java) { FixPriceSource(http).offers(city.copy(region = "")) }
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `Moscow or missing city response header is rejected`() {
        listOf(mapOf("X-City" to "3"), emptyMap()).forEach { headers ->
            val http = FixtureHttp { HttpResponse(200, fixture("fix-category-success.json"), headers) }
            assertThrows(SourceException::class.java) { FixPriceSource(http).offers(city) }
        }
    }

    @Test
    fun `response city headers are case insensitive`() {
        val http = FixtureHttp {
            HttpResponse(200, fixture("fix-category-success.json"), mapOf("x-cItY" to "111", "x-cOuNt" to "2"))
        }
        assertEquals(2, FixPriceSource(http).offers(city).size)
    }

    @Test
    fun `inactive forbidden zero stock and non beer products are skipped`() {
        val rows = JsonArray()
        rows.add(product(1))
        rows.add(product(1).apply { addProperty("active", false) })
        rows.add(product(1).apply { addProperty("forbidden", true) })
        rows.add(product(1).apply { addProperty("inStock", 0) })
        rows.add(product(1).apply { addProperty("inStock", -1) })
        listOf("Вино красное, 0,75 л", "Виски, 0,5 л, 40%", "Чипсы к пиву, 100 г").forEach { title ->
            rows.add(product(1).apply { addProperty("title", title) })
        }

        val offers = FixPriceSource(FixtureHttp { response(rows) }).offers(city)

        assertEquals(1, offers.size)
        assertEquals(8700, offers.single().priceKopecks)
    }

    @Test
    fun `malformed nonintegral overflowing and nonpositive money fails without rounding`() {
        listOf("87,00", "broken", "87.001", "21474836.48", "0", "-1").forEach { value ->
            val rows = JsonArray().apply { add(product(1).apply { addProperty("price", value) }) }
            assertThrows("price=$value", SourceException::class.java) {
                FixPriceSource(FixtureHttp { response(rows) }).offers(city)
            }
        }
        val booleanPrice = JsonArray().apply { add(product(1).apply { addProperty("price", true) }) }
        assertThrows(SourceException::class.java) {
            FixPriceSource(FixtureHttp { response(booleanPrice) }).offers(city)
        }
    }

    @Test
    fun `malformed card and range prices are rejected too`() {
        listOf("minPrice", "maxPrice", "card").forEach { field ->
            val row = product(1).apply {
                if (field == "card") getAsJsonObject("specialPrice").addProperty("price", "84.001")
                else addProperty(field, "87.001")
            }
            val rows = JsonArray().apply { add(row) }
            assertThrows(SourceException::class.java) {
                FixPriceSource(FixtureHttp { response(rows) }).offers(city)
            }
        }
    }

    @Test
    fun `ordinary range uses lower and upper bounds separately from card price`() {
        val rows = JsonArray().apply {
            add(product(1).apply {
                addProperty("minPrice", "85.00")
                addProperty("maxPrice", "90.00")
            })
        }

        val offer = FixPriceSource(FixtureHttp { response(rows) }).offers(city).single()

        assertEquals(8500, offer.priceKopecks)
        assertEquals(9000, offer.maxPriceKopecks)
        assertEquals(8400, offer.cardPriceKopecks)
        assertNull(offer.oldPriceKopecks)
    }

    @Test
    fun `empty optional special price is absent and inverted range fails`() {
        val row = product(1).apply { getAsJsonObject("specialPrice").addProperty("price", "") }
        val rows = JsonArray().apply { add(row) }
        assertNull(FixPriceSource(FixtureHttp { response(rows) }).offers(city).single().cardPriceKopecks)

        row.addProperty("minPrice", "90.00")
        row.addProperty("maxPrice", "85.00")
        assertThrows(SourceException::class.java) {
            FixPriceSource(FixtureHttp { response(rows) }).offers(city)
        }
    }

    @Test
    fun `expired finite card promotion preserves ordinary offer without card price or expiry`() {
        val rows = promotionRows(activeTo = "2026-11-03 11:59:59")

        val offer = FixPriceSource(FixtureHttp { response(rows) }) { PROMOTION_NOW }.offers(city).single()

        assertEquals(8700, offer.priceKopecks)
        assertNull(offer.cardPriceKopecks)
        assertNull(offer.validUntil)
        assertNull(offer.oldPriceKopecks)
    }

    @Test
    fun `finite future expiry keeps current card price and exact source expiry using injected clock`() {
        val rows = promotionRows(
            activeTo = "2026-11-03 12:00:01",
            activeFrom = "2026-11-03 11:59:59"
        )

        val offer = FixPriceSource(FixtureHttp { response(rows) }) { PROMOTION_NOW }.offers(city).single()

        assertEquals(8700, offer.priceKopecks)
        assertEquals(8400, offer.cardPriceKopecks)
        assertEquals("2026-11-03 12:00:01", offer.validUntil)
        assertNull(offer.oldPriceKopecks)
    }

    @Test
    fun `year 2999 sentinel keeps card price without inventing an expiry`() {
        val rows = promotionRows(activeTo = "2999-12-31 23:59:59")

        val offer = FixPriceSource(FixtureHttp { response(rows) }) { PROMOTION_NOW }.offers(city).single()

        assertEquals(8700, offer.priceKopecks)
        assertEquals(8400, offer.cardPriceKopecks)
        assertNull(offer.validUntil)
    }

    @Test
    fun `future active from hides promotion for finite and sentinel expiries`() {
        listOf("2026-11-04 12:00:00", "2999-12-31 23:59:59").forEach { expiry ->
            val rows = promotionRows(activeTo = expiry, activeFrom = "2026-11-03 12:00:01")

            val offer = FixPriceSource(FixtureHttp { response(rows) }) { PROMOTION_NOW }.offers(city).single()

            assertEquals(8700, offer.priceKopecks)
            assertNull(offer.cardPriceKopecks)
            assertNull(offer.validUntil)
        }
    }

    @Test
    fun `promotion starts inclusively and ends exclusively`() {
        val rows = promotionRows(activeTo = "2026-11-03 12:00:01", activeFrom = "2026-11-03 12:00:00")
        val source = { at: Long -> FixPriceSource(FixtureHttp { response(rows) }) { at }.offers(city).single() }

        assertNull(source(PROMOTION_NOW - 1).cardPriceKopecks)
        assertEquals(8400, source(PROMOTION_NOW).cardPriceKopecks)
        assertEquals(8400, source(PROMOTION_NOW + 999).cardPriceKopecks)
        val ended = source(PROMOTION_NOW + 1000)
        assertEquals(8700, ended.priceKopecks)
        assertNull(ended.cardPriceKopecks)
        assertNull(ended.validUntil)
    }

    @Test
    fun `strict promotion dates do not roll invalid fields or accept trailing data`() {
        listOf(
            "2026-02-30 12:00:00", "2026-11-03 25:00:00", "2026-11-03 12:00:00junk",
            "2026-1-03 12:00:00", "2999-02-30 23:59:59"
        ).forEach { invalid ->
            listOf(
                promotionRows(activeTo = invalid),
                promotionRows(activeTo = "2999-12-31 23:59:59", activeFrom = invalid)
            ).forEach { rows ->
                val offer = FixPriceSource(FixtureHttp { response(rows) }) { PROMOTION_NOW }.offers(city).single()
                assertEquals(8700, offer.priceKopecks)
                assertNull("Invalid promotion date $invalid", offer.cardPriceKopecks)
                assertNull(offer.validUntil)
            }
        }
    }

    @Test
    fun `missing or empty expiry does not create a validity date`() {
        listOf(null, "").forEach { expiry ->
            val rows = promotionRows(activeTo = expiry)
            val offer = FixPriceSource(FixtureHttp { response(rows) }) { PROMOTION_NOW }.offers(city).single()
            assertEquals(8400, offer.cardPriceKopecks)
            assertNull(offer.validUntil)
        }
    }

    @Test
    fun `finite expiry is not exposed when card price is absent`() {
        val rows = promotionRows(activeTo = "2026-11-03 12:00:01").apply {
            get(0).asJsonObject.getAsJsonObject("specialPrice").addProperty("price", "")
        }
        val offer = FixPriceSource(FixtureHttp { response(rows) }) { PROMOTION_NOW }.offers(city).single()

        assertEquals(8700, offer.priceKopecks)
        assertNull(offer.cardPriceKopecks)
        assertNull(offer.validUntil)
    }

    @Test
    fun `nonofficial and insecure product URLs are rejected`() {
        listOf("https://evil.example/p", "//evil.example/p", "http://fix-price.com/catalog/p", "../outside").forEach { url ->
            val rows = JsonArray().apply { add(product(1).apply { addProperty("url", url) }) }
            assertThrows("url=$url", SourceException::class.java) {
                FixPriceSource(FixtureHttp { response(rows) }).offers(city)
            }
        }
    }

    @Test
    fun `count header loads remaining pages counting raw products before filtering`() {
        val http = FixtureHttp { request ->
            val page = page(request)
            when (page) {
                1 -> response(fullPage(page).apply { get(0).asJsonObject.addProperty("inStock", 0) }, count = "25")
                2 -> response(JsonArray().apply { add(product(0)) }, count = "25")
                else -> throw AssertionError("Unexpected page $page")
            }
        }

        val offers = FixPriceSource(http).offers(city)

        assertEquals(24, offers.size)
        assertEquals(listOf(1, 2), http.requests.filter { it.method == "POST" }.map(::page))
    }

    @Test
    fun `exact full page count stops without unnecessary next request`() {
        val http = FixtureHttp { request -> response(fullPage(page(request)), count = "24") }
        assertEquals(24, FixPriceSource(http).offers(city).size)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `missing count follows full page then stops on short page`() {
        val http = FixtureHttp { request ->
            if (page(request) == 1) response(fullPage(1), count = null)
            else response(JsonArray().apply { add(product(0)) }, count = null)
        }
        assertEquals(25, FixPriceSource(http).offers(city).size)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun `short page below advertised count is an error not silent partial results`() {
        val http = FixtureHttp { response(JsonArray().apply { add(product(0)) }, count = "25") }
        assertThrows(SourceException::class.java) { FixPriceSource(http).offers(city) }
    }

    @Test
    fun `pagination limit throws instead of returning twenty incomplete pages`() {
        val http = FixtureHttp { request -> response(fullPage(page(request)), count = null) }
        assertThrows(SourceException::class.java) { FixPriceSource(http).offers(city) }
        assertEquals(20, http.requests.count { it.method == "POST" })
    }

    @Test
    fun `city mismatch on a later page discards all results`() {
        val http = FixtureHttp { request ->
            if (page(request) == 1) response(fullPage(1), count = "25")
            else HttpResponse(200, "[]", mapOf("X-City" to "3", "X-Count" to "25"))
        }
        assertThrows(SourceException::class.java) { FixPriceSource(http).offers(city) }
    }

    @Test
    fun `403 and 429 are blocked at lookup or catalog and are not retried`() {
        listOf(403, 429).forEach { status ->
            val lookupHttp = FixtureHttp(cityStatus = status)
            val lookupError = assertThrows(SourceException::class.java) { FixPriceSource(lookupHttp).offers(city) }
            assertTrue(lookupError.blocked)
            assertEquals(1, lookupHttp.requests.size)
            val catalogHttp = FixtureHttp { HttpResponse(status, "blocked") }
            val catalogError = assertThrows(SourceException::class.java) { FixPriceSource(catalogHttp).offers(city) }
            assertTrue(catalogError.blocked)
            assertEquals(2, catalogHttp.requests.size)
        }
    }

    @Test
    fun `server errors malformed JSON and wrapped catalog are errors rather than empty success`() {
        listOf(
            HttpResponse(500, "error"),
            HttpResponse(200, "not json", mapOf("X-City" to "111")),
            HttpResponse(200, "{\"result\":[]}", mapOf("X-City" to "111"))
        ).forEach { response ->
            val error = assertThrows(SourceException::class.java) {
                FixPriceSource(FixtureHttp { response }).offers(city)
            }
            assertFalse(error.blocked)
        }
    }

    private class FixtureHttp(
        private val cityBody: String = fixture("fix-city-balashikha.json"),
        private val cityStatus: Int = 200,
        private val catalog: (HttpRequest) -> HttpResponse = {
            HttpResponse(200, fixture("fix-category-success.json"), mapOf("X-City" to "111", "X-Count" to "2"))
        }
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val uri = URI(request.url)
            assertEquals("https", uri.scheme)
            assertEquals("api.fix-price.com", uri.host)
            return when (uri.path) {
                "/buyer/v1/location/city" -> {
                    assertEquals("GET", request.method)
                    HttpResponse(cityStatus, cityBody)
                }
                "/buyer/v1/product/in/produkty-i-napitki/alkogolnaya-produktsiya" -> {
                    assertEquals("POST", request.method)
                    assertEquals("application/json", request.headers["Content-Type"])
                    assertEquals("ru", request.headers["x-language"])
                    assertEquals("111", request.headers["x-city"])
                    catalog(request)
                }
                else -> throw AssertionError("Unexpected URL ${request.url}")
            }
        }
    }

    private companion object {
        // The API's unzoned catalog timestamps are interpreted in Europe/Moscow.
        // 2026-11-03 12:00:00 MSK == 2026-11-03 09:00:00 UTC.
        const val PROMOTION_NOW = 1793696400000L

        fun fixture(name: String): String = requireNotNull(FixPriceSourceTest::class.java.getResource("/remote/$name"))
            .readText(Charsets.UTF_8)

        fun product(index: Int): JsonObject = JsonParser.parseString(fixture("fix-category-success.json"))
            .asJsonArray[index].asJsonObject.deepCopy()

        fun promotionRows(activeTo: String?, activeFrom: String? = null): JsonArray = JsonArray().apply {
            add(product(1).apply {
                getAsJsonObject("specialPrice").apply {
                    if (activeTo == null) remove("active_to") else addProperty("active_to", activeTo)
                    if (activeFrom == null) remove("active_from") else addProperty("active_from", activeFrom)
                }
            })
        }

        fun response(rows: JsonArray, count: String? = rows.size().toString()): HttpResponse = HttpResponse(
            200, rows.toString(), buildMap {
                put("X-City", "111")
                count?.let { put("X-Count", it) }
            }
        )

        fun page(request: HttpRequest): Int = URI(request.url).rawQuery.split('&')
            .single { it.startsWith("page=") }.substringAfter('=').toInt()

        fun fullPage(page: Int): JsonArray = JsonArray().apply {
            repeat(24) { index ->
                add(product(1).apply {
                    val sku = "${page}000$index"
                    addProperty("id", sku.toInt())
                    addProperty("sku", sku)
                })
            }
        }
    }
}
