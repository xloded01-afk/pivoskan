package ru.pivoskan.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import ru.pivoskan.app.data.scan.Cancellable
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class ProductImageLoader(context: Context) {
    private val directory = File(context.cacheDir, "product-images")
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val jobs = java.util.WeakHashMap<ImageView, Cancellable>()
    fun clear(target: ImageView) { jobs.remove(target)?.cancel(); target.tag = null; target.setImageDrawable(null) }
    fun load(url: String?, target: ImageView): Cancellable {
        clear(target)
        if (!ProductImagePolicy.allowed(url)) return Cancellable { }
        val address = requireNotNull(url)
        val token = Any(); target.tag = token
        memory.get(address)?.let { target.setImageBitmap(it); return Cancellable { } }
        val cancelled = AtomicBoolean(false)
        val future = executor.submit {
            val bitmap = runCatching {
                val key = MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                directory.mkdirs()
                val file = File(directory, key)
                val bytes = if (file.isFile && file.length() <= MAX_BODY) {
                    file.setLastModified(System.currentTimeMillis()); file.readBytes()
                } else download(address)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                val result = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }))
                synchronized(directory) {
                    if (!file.exists()) file.writeBytes(bytes)
                    var size = directory.listFiles().orEmpty().sumOf { it.length() }
                    directory.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach {
                        if (size > 32L * 1024 * 1024) { val length = it.length(); if (it.delete()) size -= length }
                    }
                }
                memory.put(address, result); result
            }.getOrNull()
            if (!cancelled.get()) main.post { if (!cancelled.get() && target.tag === token) target.setImageBitmap(bitmap) }
        }
        return Cancellable { cancelled.set(true); future.cancel(true) }.also { jobs[target] = it }
    }
    private fun download(initial: String): ByteArray {
        var address = initial
        repeat(4) { redirect ->
            require(ProductImagePolicy.allowed(address))
            val connection = URL(address).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000; connection.readTimeout = 10000
                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirect < 3)
                    address = URL(URL(address), requireNotNull(connection.getHeaderField("Location"))).toString()
                } else {
                    require(status == 200 && connection.contentLengthLong <= MAX_BODY)
                    return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= MAX_BODY); output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            } finally { connection.disconnect() }
        }
        error("redirect limit")
    }
    fun close() { jobs.values.toList().forEach { it.cancel() }; jobs.clear(); executor.shutdownNow() }
    private companion object { const val MAX_BODY = 2 * 1024 * 1024 }
}
