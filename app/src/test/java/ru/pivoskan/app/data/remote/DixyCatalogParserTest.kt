package ru.pivoskan.app.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.PriceScope

class DixyCatalogParserTest {
    private val city = City("balashikha", "Балашиха", "Московская область")
    private val parser = DixyCatalogParser { FETCHED_AT }

    @Test fun `five evidence-derived prices preserve exact amounts and source metadata`() {
        val offers = parser.parse(FIXTURE, city)
        assertEquals(listOf(10990, 8499, 12990, 8899, 18990), offers.map { it.priceKopecks })
        assertEquals(listOf(12990, 9399, 14990, null, 21999), offers.map { it.oldPriceKopecks })
        assertEquals(listOf(500, 450, 500, 450, 440), offers.map { it.beer.volumeMl })
        offers.forEachIndexed { index, offer ->
            val source = fixture().getAsJsonArray("cards")[index].asJsonObject
            assertEquals(source["title"].asString, offer.beer.title)
            assertEquals(source["url"].asString, offer.sourceUrl)
            assertEquals(CityCatalog.store("dixy"), offer.store)
            assertEquals("balashikha", offer.cityId)
            assertEquals(ADDRESS, offer.address)
            assertEquals(PriceScope.STORE, offer.scope)
            assertEquals(FETCHED_AT, offer.fetchedAt)
            assertTrue(offer.shopId!!.startsWith("dixy-"))
            assertTrue(offer.id.contains(offer.shopId!!))
            assertTrue(offer.id.endsWith("-${source["id"].asString}"))
            assertNull(offer.cardPriceKopecks)
            assertNull(offer.validUntil)
            assertNull(offer.maxPriceKopecks)
            assertEquals("", offer.hours)
            assertFalse(offer.cached)
        }
        assertEquals(1, offers.map { it.shopId }.distinct().size)
        assertEquals(5, offers.map { it.id }.distinct().size)
        assertEquals("Пивной напиток Abbe Brune 6,5% 0,45л", offers[3].beer.title)
    }

    @Test fun `five confirmed Moscow cards accept their selected store but never Balashikha`() {
        // The parent observed these same five cards after selecting this PUBLIC Moscow shop.
        // This is a five-card excerpt of its 29-card rendered beer category, not Balashikha evidence.
        val root = fixture().apply {
            addProperty("pageUrl", "https://dixy.ru/catalog/alkogolnye-napitki/pivo/")
            addProperty("address", MOSCOW_ADDRESS)
            getAsJsonArray("cards")[3].asJsonObject.addProperty("oldPriceRub", "")
        }
        val offers = parser.parse(root.toString(), City("msk", "Москва"))
        assertEquals(listOf(10990, 8499, 12990, 8899, 18990), offers.map { it.priceKopecks })
        assertEquals(listOf(12990, 9399, 14990, null, 21999), offers.map { it.oldPriceKopecks })
        assertTrue(offers.all { it.cityId == "msk" && it.address == MOSCOW_ADDRESS && it.scope == PriceScope.STORE })
        expectSourceError("city") { parser.parse(root.toString(), city) }
    }

    @Test fun `shop and offer identities are stable across parses prices and time but differ by address`() {
        val first = parse(catalog(card())).single()
        val repeated = DixyCatalogParser { FETCHED_AT + 1 }.parse(
            catalog(card().apply { addProperty("priceRub", "99.99") }).toString(), city
        ).single()
        val otherShop = parse(catalog(card(), address = ADDRESS.replace("999", "998"))).single()
        assertEquals(first.id, repeated.id)
        assertEquals(first.shopId, repeated.shopId)
        assertEquals(first.beer.id, repeated.beer.id)
        assertNotEquals(first.id, otherShop.id)
        assertNotEquals(first.shopId, otherShop.shopId)
    }

    @Test fun `exact source address is retained and one clock value covers every offer`() {
        var calls = 0
        val sourceAddress = "  $ADDRESS  "
        val offers = DixyCatalogParser { FETCHED_AT + calls++ }.parse(
            fixture().apply { addProperty("address", sourceAddress) }.toString(), city
        )
        assertEquals(1, calls)
        assertTrue(offers.all { it.address == sourceAddress && it.fetchedAt == FETCHED_AT })
    }

    @Test fun `missing blank nonstring and choose-address placeholders fail descriptively`() {
        listOf<JsonElement?>(
            null, JsonNull.INSTANCE, JsonPrimitive(123), JsonPrimitive(""), JsonPrimitive(" \u00a0 "),
            JsonPrimitive("Балашиха, выберите адрес"),
            JsonPrimitive("Московская обл., Балашиха, УКАЖИ АДРЕС"),
            JsonPrimitive("Московская обл., Балашиха, адрес не выбран"),
            JsonPrimitive("Укажи способ получения заказа, Московская обл., Балашиха")
        ).forEach { address ->
            val root = catalog(card()).apply {
                if (address == null) remove("address") else add("address", address)
            }
            expectSourceError("address") { parse(root) }
        }
    }

    @Test fun `other city and Moscow-region addresses without region are rejected`() {
        listOf(
            "Московская область, Реутов, ул. Ленина, д. 1",
            "Московская обл., Балашихинская, ул. Ленина, д. 1",
            "г. Балашиха, ул. Ленина, д. 1",
            "Тульская область, Балашиха, ул. Ленина, д. 1"
        ).forEach { address -> expectSourceError("city") { parse(catalog(card(), address = address)) } }
    }

    @Test fun `Moscow requires a whole Unicode city name rather than a word fragment`() {
        val moscow = City("msk", "Москва")
        listOf(
            "Московская область, Химки, ул. Московская, д. 1",
            "НоваяМосква, ул. Ленина, д. 1",
            "Москва2, ул. Ленина, д. 1",
            "Москва_центр, ул. Ленина, д. 1"
        ).forEach { address ->
            expectSourceError("city") { parser.parse(catalog(card(), address = address).toString(), moscow) }
        }
        val offer = parser.parse(catalog(card(), address = "г. МОСКВА, ул. Ленина, д. 1").toString(), moscow).single()
        assertEquals("msk", offer.cityId)
    }

    @Test fun `city matcher accepts yo normalization and multiword names`() {
        listOf(
            City("korolev", "Королёв", "Московская область") to "Московская обл., Королев, ул. Ленина, д. 1",
            City("nnv", "Нижний Новгород") to "г. Нижний Новгород, ул. Ленина, д. 1"
        ).forEach { (selected, address) ->
            assertEquals(selected.id, parser.parse(catalog(card(), address = address).toString(), selected).single().cityId)
        }
    }

    @Test fun `catalog page must be absolute HTTPS on the exact trusted host and route`() {
        unsafeUrls("catalog").forEach { url ->
            expectSourceError("pageUrl") { parse(catalog(card(), pageUrl = url)) }
        }
        listOf("https://dixy.ru/catalog/", "https://dixy.ru:443/catalog/pivo/?page=2").forEach { url ->
            assertEquals(1, parse(catalog(card(), pageUrl = url)).size)
        }
    }

    @Test fun `untrusted or empty product routes are skipped without losing good cards`() {
        (unsafeUrls("product") + "https://dixy.ru/product/").forEach { url ->
            val bad = card().apply { addProperty("url", url) }
            assertEquals(listOf(8899), parse(catalog(bad, goodCard())).map { it.priceKopecks })
        }
        assertEquals(1, parse(catalog(card().apply {
            addProperty("url", "https://dixy.ru:443/product/beer-2000005589/")
        })).size)
    }

    @Test fun `ordinary decimal strings and JSON numbers convert to exact integer kopecks`() {
        listOf(
            "\"0.01\"" to 1, "\"0,10\"" to 10, "\"109\"" to 10900,
            "\"109.9\"" to 10990, "109.90" to 10990, "84.99" to 8499,
            "\"21474836.47\"" to Int.MAX_VALUE
        ).forEach { (price, expected) ->
            assertEquals(expected, parse(catalog(card().apply {
                add("priceRub", JsonParser.parseString(price))
            })).single().priceKopecks)
        }
    }

    @Test fun `malformed nonpositive overflowing and excess-precision current prices are skipped`() {
        invalidMoney().forEach { price ->
            val bad = card().apply {
                if (price == null) remove("priceRub") else add("priceRub", JsonParser.parseString(price))
            }
            assertEquals("price $price", listOf(8899), parse(catalog(bad, goodCard())).map { it.priceKopecks })
        }
    }

    @Test fun `malformed old prices do not make a trustworthy card`() {
        invalidMoney().filterNot { it == null || it == "null" || it == "\"\"" }.forEach { price ->
            val bad = card().apply { add("oldPriceRub", JsonParser.parseString(price)) }
            assertEquals("old price $price", listOf(8899), parse(catalog(bad, goodCard())).map { it.priceKopecks })
        }
    }

    @Test fun `old price is optional and only retained when strictly greater than current`() {
        listOf(null to null, "" to null, " \u00a0 " to null, "109.90" to null, "99.90" to null, "129.90" to 12990).forEach { (old, expected) ->
            val row = card().apply {
                if (old == null) remove("oldPriceRub") else addProperty("oldPriceRub", old)
            }
            val offer = parse(catalog(row)).single()
            assertEquals(10990, offer.priceKopecks)
            assertEquals(expected, offer.oldPriceKopecks)
            assertNull(offer.validUntil)
        }
        assertNull(parse(catalog(card().apply { add("oldPriceRub", JsonNull.INSTANCE) })).single().oldPriceKopecks)
    }

    @Test fun `loyalty coupon and personal badges never turn crossed prices into regular offers`() {
        listOf("по карте", "Цена ПО\u00a0КАРТЕ", "С купоном", "КУПОН -20%", "Персональная цена").forEach { badge ->
            val bad = card().apply { add("badges", JsonArray().apply { add(badge) }) }
            assertEquals(listOf(8899), parse(catalog(bad, goodCard())).map { it.priceKopecks })
            expectSourceError("no valid") { parse(catalog(bad)) }
        }
        assertEquals(10990, parse(catalog(card())).single().priceKopecks)
    }

    @Test fun `only beer and beer-drink prefixes survive wine and keyword search matches`() {
        listOf(
            "Вино красное 0,75л", "Сидр 0,5л", "Сухарики к пиву 100г", "Бокал Пиво 500мл",
            "Пивоварня 500мл", "Пивоход 500мл", "Пивной напитоковый 500мл", "Пиво<script>bad</script>"
        ).forEach { title ->
            assertEquals(listOf(8899), parse(catalog(card().apply {
                addProperty("title", title)
            }, goodCard())).map { it.priceKopecks })
        }
        assertEquals(10990, parse(catalog(card().apply {
            addProperty("title", "пИвО светлое 0,5л")
        })).single().priceKopecks)
    }

    @Test fun `malformed card fields are skipped rather than coerced`() {
        val malformed = mutableListOf<JsonElement>(JsonNull.INSTANCE, JsonPrimitive("card"), JsonArray())
        listOf("id", "title", "url", "badges").forEach { field ->
            malformed += card().apply { remove(field) }
            malformed += card().apply { add(field, JsonNull.INSTANCE) }
            malformed += card().apply { addProperty(field, true) }
        }
        listOf("", " ", "id/../../bad").forEach { id ->
            malformed += card().apply { addProperty("id", id) }
        }
        malformed += card().apply { add("badges", JsonArray().apply { add(123) }) }
        malformed += card().apply { add("badges", JsonArray().apply { add(JsonObject()) }) }
        assertEquals(listOf(8899), parse(catalog(*(malformed + goodCard()).toTypedArray())).map { it.priceKopecks })
    }

    @Test fun `repeated same product and price yield one offer including Cyrillic product IDs`() {
        val row = fixture().getAsJsonArray("cards")[2].asJsonObject
        val offers = parse(catalog(row, row.deepCopy(), goodCard(), row.deepCopy()))
        assertEquals(listOf(12990, 8899), offers.map { it.priceKopecks })
        assertTrue(offers.first().id.endsWith("-ЦТ0020568"))
    }

    @Test fun `conflicting duplicate prices discard that ID permanently and independently of order`() {
        val ordinary = card()
        val conflicting = card().apply { addProperty("priceRub", "119.90") }
        listOf(
            catalog(ordinary, conflicting, ordinary.deepCopy(), goodCard()),
            catalog(conflicting, ordinary, conflicting.deepCopy(), goodCard())
        ).forEach { root -> assertEquals(listOf(8899), parse(root).map { it.priceKopecks }) }
        expectSourceError("no valid") { parse(catalog(ordinary, conflicting)) }
    }

    @Test fun `duplicate metadata conflicts are discarded conservatively even at the same price`() {
        listOf(
            card().apply { addProperty("title", "Пиво другая марка 0,5л") },
            card().apply { addProperty("url", "https://dixy.ru/product/another-product/") },
            card().apply { addProperty("oldPriceRub", "139.90") }
        ).forEach { conflict ->
            assertEquals(listOf(8899), parse(catalog(card(), conflict, goodCard())).map { it.priceKopecks })
        }
    }

    @Test fun `same title and price with distinct product IDs remain separate offers`() {
        val offers = parse(catalog(card(), card().apply { addProperty("id", "different-sku") }))
        assertEquals(2, offers.size)
        assertNotEquals(offers[0].id, offers[1].id)
    }

    @Test fun `malformed envelopes and zero usable cards are source failures`() {
        listOf("", "not JSON", "{", "[]", "null", "{}", FIXTURE + " garbage").forEach { json ->
            expectSourceError { parser.parse(json, city) }
        }
        listOf<JsonElement?>(null, JsonNull.INSTANCE, JsonObject(), JsonPrimitive("cards")).forEach { cards ->
            val root = catalog(card()).apply {
                if (cards == null) remove("cards") else add("cards", cards)
            }
            expectSourceError("cards") { parse(root) }
        }
        expectSourceError("no valid") { parse(catalog()) }
        expectSourceError("no valid") { parse(catalog(card().apply { addProperty("priceRub", "0") })) }
    }

    @Test fun `nonstandard JSON syntax and duplicate fields cannot supply ambiguous catalog data`() {
        listOf(
            FIXTURE.replace("\"pageUrl\":", "'pageUrl':"),
            FIXTURE.replace("\"cards\":", "cards:"),
            "/* catalog */$FIXTURE",
            FIXTURE + FIXTURE,
            FIXTURE.replace("\"только самовывоз\"]", "\"только самовывоз\",]"),
            FIXTURE.replace("\"pageUrl\":", "\"pageUrl\":\"https://evil.example/catalog/\",\"pageUrl\":")
        ).forEach { json -> expectSourceError { parser.parse(json, city) } }
    }

    @Test fun `oversized catalog and excessive card count fail instead of silently returning a prefix`() {
        val oversized = catalog(card()).apply { addProperty("unused", "x".repeat(1_000_001)) }
        expectSourceError("limit") { parse(oversized) }
        val rows = JsonArray().apply { repeat(501) { add(goodCard()) } }
        expectSourceError("limit") { parse(catalog().apply { add("cards", rows) }) }
    }

    @Test fun `deeply nested unknown data is rejected within parser bounds`() {
        val nested = "[".repeat(1000) + "0" + "]".repeat(1000)
        val body = FIXTURE.trimEnd().dropLast(1) + ",\"unused\":$nested}"
        expectSourceError("limit") { parser.parse(body, city) }
    }

    private fun parse(root: JsonObject) = parser.parse(root.toString(), city)

    private fun fixture() = JsonParser.parseString(FIXTURE).asJsonObject
    private fun card() = fixture().getAsJsonArray("cards")[0].asJsonObject.deepCopy()
    private fun goodCard() = fixture().getAsJsonArray("cards")[3].asJsonObject.deepCopy()

    private fun catalog(vararg cards: JsonElement, address: String = ADDRESS, pageUrl: String = PAGE_URL) =
        JsonObject().apply {
            addProperty("pageUrl", pageUrl)
            addProperty("address", address)
            add("cards", JsonArray().apply { cards.forEach { add(it) } })
        }

    private fun expectSourceError(fragment: String? = null, block: () -> Unit): SourceException {
        try {
            block()
        } catch (error: SourceException) {
            assertFalse(error.blocked)
            assertTrue(error.message.orEmpty().startsWith("Dixy:"))
            if (fragment != null) assertTrue(error.message, error.message.orEmpty().contains(fragment, ignoreCase = true))
            return error
        }
        throw AssertionError("Expected descriptive SourceException")
    }

    private fun invalidMoney(): List<String?> = listOf(
        null, "null", "true", "{}", "[]", "\"\"", "\"NaN\"", "\"Infinity\"", "\"12 RUB\"",
        "\"1 000.00\"", "\"1.2.3\"", "\".99\"", "\"1.\"", "\"+1\"", "\"1e2\"", "1e2",
        "\"0\"", "\"0.00\"", "0", "-1", "\"-0.01\"", "\"1.001\"", "\"1.000\"", "1.000",
        "\"21474836.48\"", "\"999999999999999999999999999999999999999999\""
    )

    private fun unsafeUrls(route: String) = listOf(
        "http://dixy.ru/$route/beer/", "https://www.dixy.ru/$route/beer/",
        "https://dixy.ru.evil.example/$route/beer/", "https://evil.example/$route/beer/",
        "https://dixy.ru@evil.example/$route/beer/", "https://user@dixy.ru/$route/beer/",
        "https://dixy.ru:444/$route/beer/", "//$route/beer/", "/$route/beer/",
        "javascript:alert(1)", "https://dixy.ru/${route}ue/beer/", "https://dixy.ru/other/beer/",
        "https://dixy.ru/$route/../other/", "https://dixy.ru/$route/%2e%2e/other/",
        "https://dixy.ru/$route/%252e%252e/other/", "https://dixy.ru/$route/a%2fb/",
        "https://dixy.ru/$route/a%5cb/", "https://dixy.ru/$route/\\evil/", "not a URL"
    )

    private companion object {
        const val FETCHED_AT = 1793664000000L
        const val PAGE_URL = "https://dixy.ru/catalog/alkogolnye-napitki/"
        // Selected-store header observed by the parent on 2026-11-03, after the public shop reload.
        const val MOSCOW_ADDRESS = "115054, Город Москва, вн.тер.г. муниципальный округ Замоскворечье, ул Валовая, д. 20"
        // Synthetic address ONLY. The five titles/prices/links/badges below are transcribed from
        // .superpowers/source-expansion/dixy-browser-evidence.json, productsFromRenderedDom.
        // That anonymous session had NO selected address: these are NOT genuine Balashikha prices.
        const val ADDRESS = "Московская обл., г. Балашиха, Синтетическая тестовая ул., д. 999"
        val FIXTURE = """
            {
              "pageUrl": "$PAGE_URL",
              "address": "$ADDRESS",
              "cards": [
                {"id":"2000005589","title":"Пиво Балтика №8 Пшеничное нефильтрованное 5% 0,5л",
                 "url":"https://dixy.ru/product/pivo-baltika-8-pshenichnoe-nefiltrovannoe-5-05l-2000005589/",
                 "priceRub":"109.90","oldPriceRub":"129.90","badges":["по акции","только самовывоз"]},
                {"id":"2000614691","title":"Пиво темное Жигули Барное Бархатное 4,5% 0,45л",
                 "url":"https://dixy.ru/product/pivo-temnoe-jiguli-barnoe-barkhatnoe-45-045l-2000614691/",
                 "priceRub":"84.99","oldPriceRub":"93.99","badges":["по акции","только самовывоз"]},
                {"id":"ЦТ0020568","title":"Пиво темное Балтика №6 Портер 7% с/б 0,5л",
                 "url":"https://dixy.ru/product/pivo-temnoe-baltika-6-porter-7-sb-05l-tst0020568/",
                 "priceRub":"129.90","oldPriceRub":"149.90","badges":["по акции","только самовывоз"]},
                {"id":"2000564438","title":"Пивной напиток Abbe Brune 6,5% 0,45л",
                 "url":"https://dixy.ru/product/pivnoy-napitok-abbe-brune-65-045l-2000564438/",
                 "priceRub":"88.99","oldPriceRub":null,"badges":["только самовывоз"]},
                {"id":"2000185106","title":"Пиво темное Guinness Draught 4,2%, ж/б 0,44л",
                 "url":"https://dixy.ru/product/pivo-temnoe-guinness-draught-42-jb-044l-2000185106/",
                 "priceRub":"189.90","oldPriceRub":"219.99","badges":["по акции","только самовывоз"]}
              ]
            }
        """.trimIndent()
    }
}
