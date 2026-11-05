package ru.pivoskan.app.data.scan

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.remote.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CityScanRuntime private constructor(context: Context) {
    val repository = AndroidCityScanRepository.get(context)
    private val serial = Executors.newSingleThreadScheduledExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val mutable = MutableLiveData<CityScanSnapshot>()
    val state: LiveData<CityScanSnapshot> = mutable
    private var currentCityId: String? = null
    private val scheduler = ScanScheduler { delay, action ->
        val task = serial.schedule(action, delay, TimeUnit.MILLISECONDS)
        Cancellable { task.cancel(false) }
    }
    val coordinator = CityScanCoordinator(repository,
        listOf(FixPriceCitySource(network),MagnitCitySource(network),UnavailableCitySource("dixy"),UnavailableCitySource("pyaterochka")),
        scheduler, onSnapshot = { mutable.postValue(it) })
    fun selectCity(cityId: String, force: Boolean = false) {
        serial.execute {
            val city = CityCatalog.city(cityId) ?: return@execute
            if (!force && currentCityId == cityId) return@execute
            currentCityId = cityId; coordinator.start(city)
        }
    }
    fun cancel() { coordinator.cancel() }
    fun restore(cityId: String) { serial.execute { mutable.postValue(repository.read(cityId)) } }
    companion object {
        @Volatile private var instance: CityScanRuntime? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: CityScanRuntime(context.applicationContext).also { instance = it }
        }
    }
}
