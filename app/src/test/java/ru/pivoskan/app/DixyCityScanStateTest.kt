package ru.pivoskan.app

import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.City

class DixyCityScanStateTest {
    private val city = City("moscow", "Москва")

    @Test fun optionalAnalyticsFailureDoesNotAbortWorkingCatalog() {
        assertFalse(DixyCityScanState.isRequiredCatalogRequest("https://dixy.ru/ajax/listing-json.php?block=product-list&sid=731"))
        assertTrue(DixyCityScanState.isRequiredCatalogRequest("https://dixy.ru/ajax/listing-json.php?block=product-list&sid=731&perPage=30&page=1"))
        assertFalse(DixyCityScanState.isRequiredCatalogRequest("https://analytics.example/ajax/listing-json.php?block=product-list&perPage=30"))
    }

    @Test fun doesNotEquateAttemptedWithCompleted() {
        val state = DixyCityScanState(3)
        state.saved()
        state.failed("Москва, Ленина, 2", "Не подтверждён адрес")
        assertEquals(1, state.completed)
        assertEquals(2, state.attempted)
        assertEquals(1, state.remaining)
        assertFalse(state.allCompleted)
        state.saved()
        assertFalse(state.allCompleted)
    }

    @Test fun onlyAllSuccessfulStoresCanCompleteCity() {
        val empty = DixyCityScanState(0)
        assertFalse(empty.allCompleted)
        val state = DixyCityScanState(2)
        state.saved()
        assertFalse(state.allCompleted)
        state.saved()
        assertTrue(state.allCompleted)
        assertThrows(IllegalArgumentException::class.java) { state.saved() }
    }

    @Test fun acceptsWhitespaceCaseAndYoButNeverAnotherHouseOrCity() {
        assertTrue(DixyCityScanState.sameAddress(city, "Москва, ул. Ёлочная, 2", " МОСКВА,  ул. елочная, 2 "))
        assertFalse(DixyCityScanState.sameAddress(city, "Москва, Ленина, 2", "Москва, Ленина, 20"))
        assertFalse(DixyCityScanState.sameAddress(city, "Химки, Ленина, 2", "Химки, Ленина, 2"))
        assertFalse(DixyCityScanState.sameAddress(city, "Москва, Ленина, 2", ""))
    }
}
