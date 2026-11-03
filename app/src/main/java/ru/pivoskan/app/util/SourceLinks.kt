package ru.pivoskan.app.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import ru.pivoskan.app.R
import java.net.URI
import java.util.Locale

/** Only official HTTPS catalog/product links may leave the app through this entry point. */
object SourceLinks {
    fun isSupported(url: String): Boolean = officialUri(url) != null

    fun open(context: Context, url: String): Boolean {
        val uri = officialUri(url)
        if (uri == null) {
            Toast.makeText(context, R.string.details_source_invalid, Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            if (!hasActivity(context)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Avoid resolveActivity: package visibility can conceal an available browser.
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            unavailable(context)
        } catch (_: SecurityException) {
            unavailable(context)
        }
    }

    private fun officialUri(url: String): Uri? = try {
        // java.net.URI rejects malformed authorities and escapes that Android Uri accepts.
        val parsed = URI(url)
        val host = parsed.host?.lowercase(Locale.ROOT)
        val officialHost = host != null && listOf("magnit.ru", "fix-price.com", "dixy.ru", "5ka.ru").any {
            host == it || host.endsWith(".$it")
        }
        if (!parsed.scheme.equals("https", ignoreCase = true) || !officialHost ||
            parsed.rawUserInfo != null || (parsed.port != -1 && parsed.port != 443)) {
            null
        } else Uri.parse(parsed.toASCIIString())
    } catch (_: java.net.URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun unavailable(context: Context): Boolean {
        Toast.makeText(context, R.string.details_source_unavailable, Toast.LENGTH_LONG).show()
        return false
    }

    private fun hasActivity(context: Context): Boolean {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return true
            val base = current.baseContext
            if (base === current) return false
            current = base
        }
        return current is Activity
    }
}
