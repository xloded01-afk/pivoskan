package ru.pivoskan.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Observer
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.scan.*

/** Owns only a user-started finite scan; never restarts after process death or boot. */
class CityScanService : Service() {
    private val runtime by lazy { CityScanRuntime.get(this) }
    private var cityId: String? = null
    private val ownership = ServiceRunOwnership()
    private var accepting = false
    private var lastText = ""
    private var wake: PowerManager.WakeLock? = null
    private val observer = Observer<CityScanSnapshot> { snapshot ->
        if (ownership.accepts(snapshot)) {
            accepting = true
            if (BackgroundScanPolicy.finished(snapshot.sources)) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            } else {
                val count = snapshot.sources.sumOf { it.succeeded }
                val total = snapshot.sources.sumOf { source -> source.discovery?.let {
                    if (it.scope == ru.pivoskan.app.data.PriceScope.CITY_CATALOG) 1 else it.shops.size
                } ?: 0 }
                val offerCount = snapshot.catalogs.sumOf { if (it.active) it.offers.size else 0 }
                val text = "Проверено: $count/$total · предложений: $offerCount"
                if (text != lastText) {
                    lastText = text
                    getSystemService(NotificationManager::class.java).notify(ID, notification(text))
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Обход магазинов", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { runtime.cancel(); stopSelf(); return START_NOT_STICKY }
        val city = intent?.getStringExtra("city")?.let(CityCatalog::city)
        if (city == null) { stopSelf(); return START_NOT_STICKY }
        val force = intent.getBooleanExtra("force", false)
        if (cityId == city.id && accepting && !force) return START_NOT_STICKY
        cityId = city.id; ownership.expect(city.id, runtime.state.value?.runId); accepting = false
        lastText = "Получаем список магазинов…"
        try {
            ServiceCompat.startForeground(this, ID, notification(lastText),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            if (wake?.isHeld != true) wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pivoskan:CityScan").apply {
                    setReferenceCounted(false); acquire(ScanPolicy.activeRunMs + 60_000)
                }
            runtime.state.removeObserver(observer)
            runtime.state.observeForever(observer)
            runtime.serviceStarted(city.id, force)
        } catch (_: RuntimeException) {
            runtime.cancel(); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, CityPickerActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CityScanService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_search)
            .setContentTitle("Пивоскан · ${cityId?.let(CityCatalog::city)?.name.orEmpty()}")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Остановить", stop).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { runtime.cancel(); stopSelf() }
    override fun onDestroy() {
        runtime.state.removeObserver(observer)
        runtime.serviceStopped()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    private companion object { const val CHANNEL = "city_scan"; const val ID = 41; const val STOP = "ru.pivoskan.STOP_SCAN" }
}
