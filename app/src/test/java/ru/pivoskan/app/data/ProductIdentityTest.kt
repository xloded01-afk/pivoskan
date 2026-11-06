package ru.pivoskan.app.data

import org.junit.Assert.*
import org.junit.Test

class ProductIdentityTest {
    @Test fun invalidGtinDoesNotJoinSources() {
        val p = ProductDescriptor("123", gtin = "4006381333932")
        assertNotEquals(ProductIdentity.key("magnit", p), ProductIdentity.key("dixy", p))
    }
    @Test fun validGtinCanJoinIdenticalDescriptors() {
        val p = ProductDescriptor("123", gtin = "4006381333931")
        assertEquals(ProductIdentity.key("magnit", p), ProductIdentity.key("dixy", p.copy(sourceProductId = "456")))
    }
    @Test fun unknownAttributeIsNotWildcard() {
        val p = ProductDescriptor("123", gtin = "4006381333931", volumeMl = 500)
        assertNotEquals(ProductIdentity.key("magnit", p), ProductIdentity.key("magnit", p.copy(volumeMl = null)))
    }
}
