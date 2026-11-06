package ru.pivoskan.app.ui

import org.junit.Assert.*
import org.junit.Test

class ProductImagePolicyTest {
    @Test fun rejectsForeignImageOrigins() {
        assertFalse(ProductImagePolicy.allowed("http://img.fix-price.com/a"))
        assertFalse(ProductImagePolicy.allowed("https://example.org/a"))
        assertTrue(ProductImagePolicy.allowed("https://img.fix-price.com/a.jpg"))
    }
    @Test fun rejectsCredentialUrl() {
        assertFalse(ProductImagePolicy.allowed("https://user:pass@img.fix-price.com/a"))
        assertFalse(ProductImagePolicy.allowed("https://img.fix-price.com/a?token=secret"))
    }
    @Test fun nullImageUsesPlaceholder() { assertFalse(ProductImagePolicy.allowed(null)) }
}
