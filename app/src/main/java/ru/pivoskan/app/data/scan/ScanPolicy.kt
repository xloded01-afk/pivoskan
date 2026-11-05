package ru.pivoskan.app.data.scan

object ScanPolicy {
    const val discoveryPageSize = 100
    const val maxDiscoveryPages = 100
    const val maxShopsPerSource = 10_000
    const val maxGoodsPages = 100
    const val maxOffersPerShop = 5_000
    const val directConnectMs = 10_000
    const val directReadMs = 15_000
    const val browserStepMs = 45_000L
    const val browserShopMs = 180_000L
    const val activeRunMs = 2_700_000L
    const val maxTransientRetries = 1
    const val retryDelayMs = 1_000L
    const val minimumRequestGapMs = 500L
    const val maxJsonBytes = 8_388_608
}
