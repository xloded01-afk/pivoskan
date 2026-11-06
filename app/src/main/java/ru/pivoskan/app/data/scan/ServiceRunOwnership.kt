package ru.pivoskan.app.data.scan

/** Ignore stale/cancellation snapshots and bind to exactly one replacement run. */
class ServiceRunOwnership {
    private var city: String? = null
    private var baseline: String? = null
    private var owned: String? = null
    fun expect(cityId: String, previousRun: String?) { city = cityId; baseline = previousRun; owned = null }
    fun accepts(snapshot: CityScanSnapshot): Boolean {
        val id = snapshot.runId ?: return false
        if (snapshot.cityId != city || id == baseline) return false
        if (owned == null) owned = id
        return owned == id
    }
}
