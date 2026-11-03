package ru.pivoskan.app.data

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ru.pivoskan.app.data.remote.DixyCatalogParser
import java.util.concurrent.Executors

data class CatalogImportState(val saving: Boolean = false, val count: Int? = null, val failed: Boolean = false)

/** Disk work belongs to the retained model, so rotation cannot drop the save result. */
class CatalogImportViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CatalogRepository.get(application)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableLiveData(CatalogImportState())
    val state: LiveData<CatalogImportState> = mutableState
    val saving: Boolean get() = mutableState.value?.saving == true

    fun save(json: String, city: City) {
        if (saving) return
        mutableState.value = CatalogImportState(saving = true)
        worker.submit {
            val result = runCatching {
                val offers = DixyCatalogParser().parse(json, city)
                repository.importCatalog(city, offers)
                offers.size
            }
            main.post { mutableState.value = result.fold(
                { CatalogImportState(count = it) }, { CatalogImportState(failed = true) }) }
        }
    }

    fun consumeResult() { if (!saving) mutableState.value = CatalogImportState() }

    override fun onCleared() {
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
    }
}
