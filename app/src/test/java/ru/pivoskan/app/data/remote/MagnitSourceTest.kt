package ru.pivoskan.app.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.PriceScope
import ru.pivoskan.app.data.RemoteShop

class MagnitSourceTest {
    private val city = City("balashikha", "Балашиха", "Московская область")
    private val shop = RemoteShop("502731", city.id, "Московская обл, Балашиха, ул Кудаковского, 11", "ежедневно 08:00–23:00")
    private val fetchedAt = 1793664000000L // 2026-11-03T00:00:00Z, independent fixed clock.

    @Test fun `captured store keeps actual address coordinates and store timetable`() {
        val http = QueueHttp(HttpResponse(200, fixture("stores")))
        val shops = MagnitSource(http).shops(city)
        assertEquals(1, shops.size)
        assertEquals("502731", shops.single().id)
        assertEquals("balashikha", shops.single().cityId)
        assertEquals("Московская обл, Балашиха, ул Кудаковского, 11, пом. 9-11,16-29,40,49-58, пом. 2, ком. 1-14", shops.single().address)
        assertEquals("ежедневно 08:00–23:00", shops.single().hours)
        assertEquals(55.820726, shops.single().latitude!!, 0.000001)
        assertEquals(37.971841, shops.single().longitude!!, 0.000001)
        assertEquals("1", shops.single().providerType)
        val request = http.requests.single()
        assertPost(request, "https://magnit.ru/webgate/v1/stores-facade/search/detail")
        assertEquals(JsonParser.parseString("""{"filters":{"query":"Балашиха","storeTypeListV2":["MM"]},"pagination":{"offset":0,"size":100},"sorting":{"sortBy":"SORT_BY_CITY","sortType":"SORT_TYPE_ASC"}}"""), JsonParser.parseString(request.body))
    }

    @Test fun `store selection rejects other city region inactive and non MM stores`() {
        val root = json("stores")
        val original = root.getAsJsonArray("data")[0].asJsonObject.deepCopy()
        var nextCode = 600000
        fun extra(change: (JsonObject) -> Unit) {
            root.getAsJsonArray("data").add(original.deepCopy().apply {
                getAsJsonObject("externalId").addProperty("storeCode", "${nextCode++}")
                change(this)
            })
        }
        extra { it.addProperty("address", "Московская обл, Химки, ул Балашиха, 1") }
        extra { it.addProperty("address", "Тульская обл, Балашиха, ул Ленина, 1") }
        extra { it.addProperty("address", "Балашиха, ул Ленина, 1") }
        extra { it.addProperty("isActive", false) }
        extra { it.addProperty("status", "STATUS_CLOSED") }
        extra { it.addProperty("storeTypeV2", "MK") }
        assertEquals(listOf("502731"), MagnitSource(QueueHttp(HttpResponse(200, root.toString()))).shops(city).map { it.id })
    }

    @Test fun `city matching normalizes yo and accepts city prefix`() {
        val body = json("stores").apply {
            getAsJsonArray("data")[0].asJsonObject.addProperty("address", "Московская обл., г. Королев, ул Ленина, 1")
        }
        val result = MagnitSource(QueueHttp(HttpResponse(200, body.toString())))
            .shops(City("korolev", "Королёв", "Московская область"))
        assertEquals(1, result.size)
        assertEquals("korolev", result.single().cityId)
    }

    @Test fun `captured goods preserve integer kopecks and selected shop provenance`() {
        val http = QueueHttp(HttpResponse(200, goods().toString()))
        val offers = MagnitSource(http) { fetchedAt }.offers(city, shop)
        assertEquals(2, offers.size)
        val first = offers.first()
        assertEquals(4899, first.priceKopecks)
        assertEquals("Пиво светлое пастеризованное Feilong 4.3% 430мл", first.beer.title)
        assertEquals("d7af4fa5811796c9ef9fa5b16f8403e9995409fda352cb4eaa352a5aecd63158", first.beer.id)
        assertEquals(430, first.beer.volumeMl)
        assertEquals("magnit", first.store.id)
        assertEquals(PriceScope.STORE, first.scope)
        assertEquals("502731", first.shopId)
        assertEquals("balashikha", first.cityId)
        assertEquals(shop.address, first.address)
        assertEquals(shop.hours, first.hours)
        assertEquals(fetchedAt, first.fetchedAt)
        assertNull(first.oldPriceKopecks)
        assertEquals("https://magnit.ru/product/8000109721-feilong_pivo_svetloe_past_4_3_0_43l_zh_b?shopCode=502731&shopType=1", first.sourceUrl)
        assertPost(http.requests.single(), "https://magnit.ru/webgate/v2/goods/search")
        assertEquals(JsonParser.parseString("""{"storeCode":"502731","storeType":"1","catalogType":"1","includeAdultGoods":true,"term":"пиво","pagination":{"limit":50,"offset":0},"sort":{"order":"asc","type":"price"}}"""), JsonParser.parseString(http.requests.single().body))
    }

    @Test fun `wrong selected city is rejected before requesting goods`() {
        val http = QueueHttp()
        expectSourceError { MagnitSource(http).offers(city, shop.copy(cityId = "msk")) }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `foreign or absent store code rejects whole payload including unavailable snacks`() {
        listOf("999999", null).forEach { code ->
            val root = goods()
            root.getAsJsonArray("items")[1].asJsonObject.apply {
                if (code == null) remove("storeCode") else addProperty("storeCode", code)
                addProperty("name", "Сухарики к пиву")
                addProperty("quantity", 0)
            }
            expectSourceError { source(root).offers(city, shop) }
        }
    }

    @Test fun `only available beer and beer drinks survive search results`() {
        val root = goods()
        val items = root.getAsJsonArray("items")
        val first = items[0].asJsonObject.deepCopy()
        listOf("Сухарики к пиву 100г", "Бокал для пива 500мл", "Сувенир Пиво 500мл", "Чипсы Пиво 100г", "Набор для пива 2шт", "Магнит Пиво 500мл", "Шампунь Пиво 500мл").forEachIndexed { i, name ->
            items.add(first.deepCopy().apply { addProperty("id", "${900 + i}"); addProperty("name", name) })
        }
        listOf(0, -1).forEachIndexed { i, quantity ->
            items.add(first.deepCopy().apply { addProperty("id", "${800 + i}"); addProperty("quantity", quantity) })
        }
        assertEquals(listOf(4899, 5999), source(root).offers(city, shop).map { it.priceKopecks })
    }

    @Test fun `descriptive beer prefixes are retained and title or volume changes keep distinct identities`() {
        val root = goods()
        val items = root.getAsJsonArray("items")
        val first = items[0].asJsonObject.deepCopy()
        items.add(first.deepCopy().apply {
            addProperty("id", "8000109722")
            addProperty("name", "Светлое пиво фильтрованное безалкогольное Bud 450мл")
        })
        items.add(first.deepCopy().apply {
            addProperty("id", "8000109723")
            addProperty("name", "Пиво светлое пастеризованное Feilong 4.3% 500мл")
        })
        val result = source(root).offers(city, shop)
        assertEquals(4, result.size)
        assertEquals(listOf(430, 430, 450, 500), result.map { it.beer.volumeMl })
        assertEquals(4, result.map { it.beer.id }.distinct().size)
    }

    @Test fun `missing quantity never implies available stock`() {
        val root = goods()
        root.getAsJsonArray("items")[0].asJsonObject.remove("quantity")
        assertEquals(listOf(5999), source(root).offers(city, shop).map { it.priceKopecks })
    }

    @Test fun `missing fractional overflow or nonpositive prices never become invented prices`() {
        listOf("null", "48.99", "2147483648", "0", "-1", "\"4899\"").forEach { price ->
            val root = goods()
            root.getAsJsonArray("items")[0].asJsonObject.add("price", JsonParser.parseString(price))
            assertEquals(listOf(5999), source(root).offers(city, shop).map { it.priceKopecks })
        }
    }

    @Test fun `old price requires a greater amount and an unexpired known promotion`() {
        listOf(
            Triple(6999, "2026-12-09T23:59:59Z", 6999),
            Triple(6999, "2026-01-01T00:00:00Z", null),
            Triple(6999, "2026-11-03T01:00:00+03:00", null),
            Triple(6999, "2026-11-03T04:00:00+03:00", 6999),
            Triple(6999, "2026-11-03", 6999),
            Triple(4899, "2026-12-09T23:59:59Z", null),
            Triple(4799, "2026-12-09T23:59:59Z", null),
            Triple(6999, "invalid-date", null),
            Triple(6999, null, 6999)
        ).forEach { (oldPrice, endDate, expected) ->
            val root = goods()
            root.getAsJsonArray("items")[0].asJsonObject.getAsJsonObject("promotion").apply {
                addProperty("oldPrice", oldPrice)
                if (endDate == null) remove("endDate") else addProperty("endDate", endDate)
            }
            assertEquals(expected, source(root).offers(city, shop).first().oldPriceKopecks)
        }
    }

    @Test fun `explicitly inactive promotion never supplies a discount`() {
        val root = goods()
        root.getAsJsonArray("items")[0].asJsonObject.getAsJsonObject("promotion").apply {
            addProperty("oldPrice", 6999)
            addProperty("isPromotion", false)
        }
        assertNull(source(root).offers(city, shop).first().oldPriceKopecks)
    }

    @Test fun `unsafe title or product route is not exposed`() {
        listOf("name" to "Пиво<script>alert(1)</script> 430мл", "seoCode" to "../../other?x=1", "id" to "1/../../other").forEach { (field, value) ->
            val root = goods()
            root.getAsJsonArray("items")[0].asJsonObject.addProperty(field, value)
            assertEquals(listOf(5999), source(root).offers(city, shop).map { it.priceKopecks })
        }
    }

    @Test fun `pagination follows nextOffset 52 after exactly 48 captured shape items`() {
        val first = goods()
        val items = first.getAsJsonArray("items")
        val template = items[0].asJsonObject.deepCopy()
        while (items.size() < 48) items.add(template.deepCopy().apply { addProperty("id", "${700000 + items.size()}") })
        first.getAsJsonObject("pagination").apply { addProperty("hasMore", true); addProperty("nextOffset", 52); addProperty("totalCount", 197) }
        val second = goods().apply {
            getAsJsonArray("items").remove(1)
            getAsJsonArray("items")[0].asJsonObject.addProperty("id", "8000109722")
            getAsJsonObject("pagination").addProperty("offset", 52)
        }
        val http = QueueHttp(HttpResponse(200, first.toString()), HttpResponse(200, second.toString()))
        assertEquals(49, MagnitSource(http).offers(city, shop).size)
        assertEquals(listOf(0, 52), http.requests.map { JsonParser.parseString(it.body).asJsonObject.getAsJsonObject("pagination")["offset"].asInt })
    }

    @Test fun `403 and 429 are blocked for either endpoint`() {
        listOf(403, 429).forEach { status ->
            assertTrue(expectSourceError { MagnitSource(QueueHttp(HttpResponse(status, "denied"))).shops(city) }.blocked)
            assertTrue(expectSourceError { MagnitSource(QueueHttp(HttpResponse(status, "denied"))).offers(city, shop) }.blocked)
        }
    }

    @Test fun `failed second page throws instead of returning first page as success`() {
        listOf(403, 429, 500).forEach { status ->
            val first = moreGoods(52)
            val error = expectSourceError { MagnitSource(QueueHttp(HttpResponse(200, first), HttpResponse(status, "failed"))).offers(city, shop) }
            assertEquals(status != 500, error.blocked)
        }
    }

    @Test fun `transport failure on second page is a source error`() {
        var calls = 0
        val http = HttpTransport { if (calls++ == 0) HttpResponse(200, moreGoods(52)) else throw java.io.IOException("offline") }
        assertFalse(expectSourceError { MagnitSource(http).offers(city, shop) }.blocked)
        assertEquals(2, calls)
    }

    @Test fun `pagination cap rejects incomplete results without requesting page 21`() {
        var calls = 0
        val http = HttpTransport { calls++; HttpResponse(200, moreGoods(calls * 52)) }
        expectSourceError { MagnitSource(http).offers(city, shop) }
        assertEquals(20, calls)
    }

    @Test fun `nonadvancing or missing cursor rejects incomplete results`() {
        listOf("0", "-1", "null", "52.5").forEach { cursor ->
            val root = JsonParser.parseString(moreGoods(52)).asJsonObject
            root.getAsJsonObject("pagination").add("nextOffset", JsonParser.parseString(cursor))
            expectSourceError { source(root).offers(city, shop) }
        }
    }

    @Test fun `malformed or incomplete envelopes are source errors rather than empty catalogs`() {
        listOf("<html>blocked</html>", "{}", "{\"items\":[]}").forEach { body ->
            assertFalse(expectSourceError { MagnitSource(QueueHttp(HttpResponse(200, body))).offers(city, shop) }.blocked)
        }
        expectSourceError { MagnitSource(QueueHttp(HttpResponse(200, "{}"))).shops(city) }
        assertTrue(MagnitSource(QueueHttp(HttpResponse(200, "{\"data\":[]}"))).shops(city).isEmpty())
        assertTrue(MagnitSource(QueueHttp(HttpResponse(200, "{\"items\":[],\"pagination\":{\"hasMore\":false}}"))).offers(city, shop).isEmpty())
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/remote/magnit-$name.json")).readText()
    private fun json(name: String) = JsonParser.parseString(fixture(name)).asJsonObject
    // Keep the captured fixture's real hasMore/totalCount; explicitly close shortened test responses.
    private fun goods() = json("goods").apply {
        getAsJsonObject("pagination").apply { addProperty("hasMore", false); addProperty("totalCount", 2) }
    }
    private fun source(root: JsonObject) = MagnitSource(QueueHttp(HttpResponse(200, root.toString()))) { fetchedAt }
    private fun moreGoods(nextOffset: Int) = goods().apply {
        getAsJsonObject("pagination").apply { addProperty("hasMore", true); addProperty("nextOffset", nextOffset) }
    }.toString()

    private fun assertPost(request: HttpRequest, url: String) {
        assertEquals(url, request.url)
        assertEquals("POST", request.method)
        assertEquals("1", request.headers.entries.first { it.key.equals("x-magnit-bff", true) }.value)
        assertEquals("application/json", request.headers.entries.first { it.key.equals("Content-Type", true) }.value)
    }

    private fun expectSourceError(block: () -> Unit): SourceException {
        try { block() } catch (error: SourceException) { return error }
        throw AssertionError("Expected SourceException, never successful partial results")
    }

    private class QueueHttp(vararg responses: HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        private val pending = java.util.ArrayDeque(responses.toList())
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            check(pending.isNotEmpty()) { "Unexpected request: ${request.url}" }
            return pending.removeFirst()
        }
    }
}
