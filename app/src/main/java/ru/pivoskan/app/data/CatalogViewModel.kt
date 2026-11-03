package ru.pivoskan.app.data

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

data class CatalogUiState(val snapshot: CatalogSnapshot?, val loading: Boolean)

/** Keeps a request through rotation; generation checks reject late results after a new selection. */
class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CatalogRepository.get(application)
    private val preferences = Prefs(application)
    private val mutableState = MutableLiveData<CatalogUiState>()
    val state: LiveData<CatalogUiState> = mutableState
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private var job: Future<*>? = null
    private var currentCityId: String? = null

    fun load(cityId: String, shopId: String? = null, force: Boolean = false) {
        if (!force && cityId == currentCityId && mutableState.value != null) return
        val city = CityCatalog.city(cityId) ?: return
        currentCityId = cityId
        val selectedId = shopId ?: preferences.magnitShopId(cityId)
        if (shopId != null) preferences.setMagnitShopId(cityId, shopId)
        val token = generation.incrementAndGet()
        job?.cancel(true)
        val cached = repository.snapshot(cityId, selectedId)?.let { value ->
            if (selectedId == null || value.selectedShop?.id == selectedId) value else value.copy(
                selectedShop = null, offers = value.offers.filter { it.store.id != "magnit" },
                sources = value.sources.filter { it.storeId != "magnit" })
        }
        mutableState.value = CatalogUiState(cached, true)
        job = executor.submit {
            try {
                val result = repository.refresh(city, selectedId) { partial -> deliver(token, cityId, partial, true) }
                deliver(token, cityId, result, false)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                main.post {
                    if (generation.get() == token) mutableState.value = CatalogUiState(cached, false)
                }
            }
        }
    }

    private fun deliver(token: Long, cityId: String, value: CatalogSnapshot, loading: Boolean) {
        main.post {
            if (generation.get() == token && currentCityId == cityId) {
                value.selectedShop?.let { preferences.setMagnitShopId(cityId, it.id) }
                repository.remember(value)
                mutableState.value = CatalogUiState(value, loading)
            }
        }
    }

    override fun onCleared() {
        generation.incrementAndGet()
        job?.cancel(true)
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
