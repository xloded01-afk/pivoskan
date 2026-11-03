package ru.pivoskan.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import android.os.Handler
import android.os.Looper
import ru.pivoskan.app.data.CatalogRepository
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.RemoteShop
import ru.pivoskan.app.data.remote.DixyCatalogParser
import java.util.concurrent.Executors

/** Retains results, never a WebView or Activity. A started atomic save finishes on cancellation. */
class DixyCityScanModel(application: Application) : AndroidViewModel(application) {
    var progress = DixyCityScanState(0)
    var shops: List<RemoteShop> = emptyList()
    var stopped = false
    var reason = ""
    var saving = false
        private set
    val scanStartedAt = System.currentTimeMillis()
    val changes = MutableLiveData(0)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val repository = CatalogRepository.get(application)

    fun save(json: String, city: City, shop: RemoteShop) {
        if (saving || stopped) return
        saving = true
        worker.execute {
            val result = runCatching {
                val offers = DixyCatalogParser().parseComplete(json, city)
                require(offers.isNotEmpty() && offers.all { DixyCityScanState.sameAddress(city, shop.address, it.address) })
                repository.importCityShop(city, offers, scanStartedAt)
            }
            main.post {
                saving = false
                result.fold({ progress.saved() }, {
                    progress.failed(shop.address, "Не удалось проверить или сохранить полный каталог")
                    stopped = true
                    reason = "Ошибка сохранения; сканирование остановлено"
                })
                changes.value = (changes.value ?: 0) + 1
            }
        }
    }

    override fun onCleared() { worker.shutdown() }
}
