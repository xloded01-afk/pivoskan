package ru.pivoskan.app.ui

import java.net.URI

object ProductImagePolicy {
    // Hosts observed in official saved catalog responses; extend only with source evidence.
    private val hosts = setOf("img.fix-price.com")
    fun allowed(url: String?): Boolean = runCatching {
        if (url == null) return false
        val uri = URI(url)
        url.length <= 2048 && uri.scheme == "https" && uri.host in hosts &&
            uri.userInfo == null && uri.port in listOf(-1, 443) && uri.fragment == null && uri.query == null
    }.getOrDefault(false)
}
