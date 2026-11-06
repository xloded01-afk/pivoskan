package ru.pivoskan.app.data.scan

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.Prefs
import ru.pivoskan.app.data.remote.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CityScanRuntime private constructor(context: Context) {
    private val context = context.applicationContext
    val repository = AndroidCityScanRepository.get(context)
    private val serial = Executors.newSingleThreadScheduledExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val mutable = MutableLiveData<CityScanSnapshot>()
    val state: LiveData<CityScanSnapshot> = mutable
    @Volatile private var currentCityId: String? = null
    @Volatile private var visible = false
    @Volatile private var requestAllowed = false
    private var started = false
    private val generation = AtomicLong()
    private val scheduler = ScanScheduler { delay, action ->
        val task = serial.schedule(action, delay, TimeUnit.MILLISECONDS)
        Cancellable { task.cancel(false) }
    }
    val coordinator = CityScanCoordinator(repository,
        listOf(FixPriceCitySource(network, { HttpsTransport(::canRequestNow) }),
            MagnitCitySource(network, { HttpsTransport(::canRequestNow) }),
            UnavailableCitySource("dixy"),UnavailableCitySource("pyaterochka")),
        scheduler, onSnapshot = { mutable.postValue(it) })
    private fun canRequestNow() = visible && requestAllowed &&
        context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    @Synchronized fun selectCity(cityId: String, force: Boolean = false) {
        val city = CityCatalog.city(cityId) ?: return
        if (currentCityId != cityId) {
            currentCityId = cityId; generation.incrementAndGet(); started = false; coordinator.cancel()
        }
        if (!visible || (!force && started)) { restore(cityId); return }
        if (force) { generation.incrementAndGet(); coordinator.cancel() }
        started = true
        val token = generation.get()
        serial.execute {
            synchronized(this) {
                if (visible && token == generation.get() && currentCityId == cityId) coordinator.start(city)
            }
        }
    }
    @Synchronized fun cancel() { generation.incrementAndGet(); coordinator.cancel() }
    @Synchronized fun visibilityChanged(value: Boolean) {
        if (visible == value) return
        visible = value; generation.incrementAndGet(); started = false
        if (!value) coordinator.cancel()
        else (currentCityId ?: Prefs(context).cityId)?.let { selectCity(it) }
    }
    @Synchronized fun requestsAllowed(value: Boolean) {
        requestAllowed = value; coordinator.setRequestAllowed(value)
    }
    fun restore(cityId: String) { serial.execute { if (currentCityId == cityId) mutable.postValue(repository.read(cityId)) } }
    fun resumeSource(sourceId: String) { serial.execute { if (canRequestNow()) coordinator.resumeSource(sourceId) } }
    companion object {
        @Volatile private var instance: CityScanRuntime? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: CityScanRuntime(context.applicationContext).also { instance = it }
        }
    }
}
