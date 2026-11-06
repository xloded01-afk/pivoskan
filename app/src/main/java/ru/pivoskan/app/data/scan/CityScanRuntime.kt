package ru.pivoskan.app.data.scan

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ru.pivoskan.app.data.CityCatalog
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
    @Volatile private var serviceRunning = false
    private var started = false
    private var screenTaskId: Int? = null
    private val sessionPrefs = this.context.getSharedPreferences("scan_launch_session", Context.MODE_PRIVATE)
    private val boot = android.provider.Settings.Global.getInt(this.context.contentResolver,
        android.provider.Settings.Global.BOOT_COUNT, 0)
    private val autoSession = AutoScanSession(
        sessionPrefs.getString("key", null).takeIf { sessionPrefs.getInt("boot", -1) == boot }
    ) { key -> check(sessionPrefs.edit().putInt("boot", boot).putString("key", key).commit()) }
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
    private fun canRequestNow() = BackgroundScanPolicy.canRequest(visible, requestAllowed &&
        context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked, serviceRunning)
    @Synchronized fun serviceStarted(cityId: String, force: Boolean) {
        serviceRunning = true
        coordinator.setRequestAllowed(true)
        beginCity(cityId, force)
    }
    @Synchronized fun serviceStopped() {
        serviceRunning = false
        generation.incrementAndGet()
        val running = mutable.value?.sources.orEmpty().any { it.phase in setOf(SourcePhase.WAITING, SourcePhase.RUNNING) }
        if (running) coordinator.cancel()
        coordinator.setRequestAllowed(requestAllowed)
    }
    @Synchronized fun selectCity(cityId: String, force: Boolean = false) {
        if (CityCatalog.city(cityId) == null) return
        val task = screenTaskId ?: return
        if (!visible) { restore(cityId); return }
        var reserved = false
        try {
            if (!autoSession.request(task, cityId, force)) { restore(cityId); return }
            reserved = true
            androidx.core.content.ContextCompat.startForegroundService(context,
                android.content.Intent(context, ru.pivoskan.app.CityScanService::class.java)
                    .putExtra("city", cityId).putExtra("force", true))
        } catch (_: RuntimeException) {
            if (reserved) runCatching { autoSession.launchFailed() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Не удалось запустить фоновый обход. Откройте приложение и повторите.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    @Synchronized fun openScreen(cityId: String, taskId: Int) {
        screenTaskId = taskId
        if (currentCityId == null) currentCityId = cityId
        selectCity(cityId)
    }
    @Synchronized private fun beginCity(cityId: String, force: Boolean) {
        val city = CityCatalog.city(cityId) ?: return
        if (currentCityId != cityId) {
            currentCityId = cityId; generation.incrementAndGet(); started = false; coordinator.cancel()
        }
        if ((!visible && !serviceRunning) || (!force && started)) { restore(cityId); return }
        if (force) { generation.incrementAndGet(); coordinator.cancel() }
        started = true
        val token = generation.get()
        serial.execute {
            synchronized(this) {
                if ((visible || serviceRunning) && token == generation.get() && currentCityId == cityId) coordinator.start(city)
            }
        }
    }
    @Synchronized fun cancel() {
        generation.incrementAndGet(); started = true; coordinator.cancel()
        context.stopService(android.content.Intent(context, ru.pivoskan.app.CityScanService::class.java))
    }
    @Synchronized fun visibilityChanged(value: Boolean) {
        if (visible == value) return
        visible = value
        // Visibility only controls access; opening a screen/task owns automatic launch.
    }
    @Synchronized fun requestsAllowed(value: Boolean) {
        requestAllowed = value; coordinator.setRequestAllowed(value || serviceRunning)
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
