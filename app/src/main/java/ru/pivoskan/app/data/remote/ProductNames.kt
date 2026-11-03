package ru.pivoskan.app.data.remote

import ru.pivoskan.app.data.Beer
import ru.pivoskan.app.util.normalize
import java.math.BigDecimal
import java.security.MessageDigest

object ProductNames {
    private val volume = Regex("(\\d+(?:[.,]\\d+)?)\\s*(мл|л)\\b", RegexOption.IGNORE_CASE)
    /** Conservative identity: no fuzzy matching of different brands, strengths or packaging. */
    fun beer(title: String, brand: String = ""): Beer {
        val normalized = normalize(title)
        val match = volume.findAll(normalized).lastOrNull()
        val millilitres = match?.let {
            runCatching {
                val amount = BigDecimal(it.groupValues[1].replace(',', '.'))
                (if (it.groupValues[2] == "л") amount.movePointRight(3) else amount).intValueExact()
            }.getOrDefault(0)
        } ?: 0
        val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return Beer(hash, brand, title, millilitres)
    }
}
