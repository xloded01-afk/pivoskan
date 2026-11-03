package ru.pivoskan.app.data

import android.content.Context
import android.util.AtomicFile
import ru.pivoskan.app.data.remote.FixPriceSource
import ru.pivoskan.app.data.remote.HttpsTransport
import ru.pivoskan.app.data.remote.MagnitSource
import java.io.File

class CatalogRepository private constructor(context: Context) {
    private val codec = SnapshotCodec()
    private val directory = File(context.filesDir, "live-catalog-v1").apply { mkdirs() }
    private val disk = object : CatalogCache {
        override fun read(cityId: String): CatalogSnapshot? = synchronized(this@CatalogRepository) {
            if (CityCatalog.city(cityId) == null) return@synchronized null
            readFile(cityId, "$cityId.json")
        }
        override fun readShop(cityId: String, shopId: String): CatalogSnapshot? = synchronized(this@CatalogRepository) {
            if (CityCatalog.city(cityId) == null || !shopId.matches(Regex("[0-9]+"))) return@synchronized null
            readFile(cityId, "$cityId-magnit-$shopId.json")?.takeIf { it.selectedShop?.id == shopId }
        }
        override fun write(snapshot: CatalogSnapshot) = synchronized(this@CatalogRepository) {
            require(CityCatalog.city(snapshot.cityId) != null)
            writeFile(snapshot, "${snapshot.cityId}.json")
            snapshot.selectedShop?.id?.takeIf { it.matches(Regex("[0-9]+")) }?.let { shopId ->
                writeFile(snapshot, "${snapshot.cityId}-magnit-$shopId.json")
            }
            Unit
        }
    }
    private val cache = CatalogMemoryCache(disk)
    private val transport = HttpsTransport()
    private val magnit = MagnitSource(transport)
    private val fix = FixPriceSource(transport)

    fun snapshot(cityId: String, shopId: String? = null): CatalogSnapshot? =
        shopId?.let { cache.readShop(cityId, it) } ?: cache.read(cityId)
    fun remember(snapshot: CatalogSnapshot) { cache.remember(snapshot) }

    private fun readFile(cityId: String, name: String): CatalogSnapshot? = runCatching {
        codec.decode(AtomicFile(File(directory, name)).readFully().toString(Charsets.UTF_8), cityId)
    }.getOrNull()

    private fun writeFile(snapshot: CatalogSnapshot, name: String) {
        val file = AtomicFile(File(directory, name))
        val output = file.startWrite()
        try {
            output.write(codec.encode(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun refresh(city: City, preferredShopId: String?, onPartial: (CatalogSnapshot) -> Unit): CatalogSnapshot =
        CatalogLoader(cache, magnit::shops, magnit::offers, fix::offers).load(city, preferredShopId, onPartial)

    companion object {
        @Volatile private var instance: CatalogRepository? = null
        fun get(context: Context): CatalogRepository = instance ?: synchronized(this) {
            instance ?: CatalogRepository(context.applicationContext).also { instance = it }
        }
    }
}
