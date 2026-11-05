package ru.pivoskan.app.data.scan

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Android storage wiring is separate from the JVM-tested repository. */
object AndroidCityScanRepository {
    @Volatile private var instance: CityScanRepository? = null
    fun get(context: Context): CityScanRepository = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }
    private fun create(context: Context): CityScanRepository {
        val directory = File(context.filesDir, "live-catalog-v2").apply { mkdirs() }
        val old = File(context.filesDir, "live-catalog-v1")
        fun readBounded(file: AtomicFile): String? = runCatching {
            file.openRead().use { stream ->
                require(stream.channel.size() <= CityScanCodec.MAX_CACHE_BYTES)
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrNull()
        return CityScanRepository(object : CityScanDisk {
            override fun read(cityId: String) = readBounded(AtomicFile(File(directory, "$cityId.json")))
            override fun write(cityId: String, json: String) {
                val file = AtomicFile(File(directory, "$cityId.json"))
                val output = file.startWrite()
                try { output.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            }
        }, legacy = { cityId ->
            old.listFiles()?.filter { it.name == "$cityId.json" || it.name.matches(Regex("${Regex.escape(cityId)}-magnit-[0-9]+\\.json")) }
                ?.take(ScanPolicy.maxShopsPerSource)?.mapNotNull { readBounded(AtomicFile(it)) }.orEmpty()
        })
    }
}
