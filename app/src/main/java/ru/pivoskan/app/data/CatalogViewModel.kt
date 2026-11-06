package ru.pivoskan.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import ru.pivoskan.app.data.scan.*

data class CatalogUiState(val snapshot: CatalogSnapshot?, val loading: Boolean, val scan: CityScanSnapshot? = null)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = CityScanRuntime.get(application)
    private var cityId: String? = null
    private val mutable = MediatorLiveData<CatalogUiState>().apply {
        addSource(runtime.state) { snapshot -> if (snapshot.cityId == cityId) {
            value = CatalogUiState(snapshot.asLegacySnapshot(), snapshot.sources.any {
                it.phase in setOf(SourcePhase.WAITING, SourcePhase.RUNNING) }, snapshot)
        } }
    }
    val state: LiveData<CatalogUiState> = mutable
    fun openScreen(cityId: String, taskId: Int) { this.cityId = cityId; runtime.openScreen(cityId, taskId) }
    fun selectCity(cityId: String) { this.cityId = cityId; runtime.selectCity(cityId) }
    fun load(cityId: String, force: Boolean = false) { this.cityId = cityId; runtime.selectCity(cityId, force) }
    fun refresh() { cityId?.let { runtime.selectCity(it, true) } }
    fun cancel() { runtime.cancel() }
    fun pauseRefresh() = cancel()
    fun restoreImported(cityId: String) { this.cityId = cityId; runtime.restore(cityId) }
    fun resumeSource(sourceId: String) { runtime.resumeSource(sourceId) }
}
