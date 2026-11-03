package ru.pivoskan.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `цена разделяется неразрывным пробелом`() {
        assertEquals("1\u00A0299\u00A0₽", formatPrice(1299))
        assertEquals("59\u00A0₽", formatPrice(59))
        assertEquals("1\u00A0000\u00A0000\u00A0₽", formatPrice(1_000_000))
    }

    @Test
    fun `объём выводится в литрах с запятой`() {
        assertEquals("0,5 л", formatVolume(500))
        assertEquals("0,33 л", formatVolume(330))
        assertEquals("0,45 л", formatVolume(450))
        assertEquals("1 л", formatVolume(1000))
    }

    @Test
    fun `нормализация приводит регистр ё и пробелы`() {
        assertEquals("жесткое", normalize("  Жёсткое  "))
        assertEquals("жигули барное", normalize("  ЖИГУЛИ\t БАРНОЕ "))
        assertEquals("", normalize("   "))
    }
}
