package ru.pivoskan.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.pivoskan.app.data.remote.ProductNames

class LiveMoneyTest {
    @Test fun `kopecks never rounded away`() {
        assertEquals("87,99\u00a0₽", formatMoney(8799))
        assertEquals("87\u00a0₽", formatMoney(8700))
        assertEquals("1\u00a0234,05\u00a0₽", formatMoney(123405))
    }
    @Test fun `product volume is parsed from label rather than invented`() {
        assertEquals(450, ProductNames.beer("Пиво Хамовники 4.8% 0,45 л").volumeMl)
        assertEquals(430, ProductNames.beer("Пиво Feilong 4.3% 430мл").volumeMl)
        assertEquals(0, ProductNames.beer("Пиво без указанного объёма").volumeMl)
        assertEquals(ProductNames.beer("ПИВО КОЗЁЛ 450мл").id, ProductNames.beer("пиво козел 450мл").id)
    }
}
