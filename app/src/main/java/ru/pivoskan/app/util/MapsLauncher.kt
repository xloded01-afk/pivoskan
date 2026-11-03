package ru.pivoskan.app.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import ru.pivoskan.app.R
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.Offer
import ru.pivoskan.app.data.PriceScope

/** Opens an external search for an actual, source-provided store address. */
object MapsLauncher {
    fun open(context: Context, offer: Offer, city: City) {
        if (offer.scope != PriceScope.STORE || offer.address.isBlank() || offer.cityId != city.id) return
        val query = Uri.encode("${offer.store.name}, ${city.name}, ${offer.address}")
        // Do not preflight with resolveActivity: Android package visibility can hide a handler.
        if (launch(context, Uri.parse("geo:0,0?q=$query"))) return
        if (launch(context, Uri.parse("https://yandex.ru/maps/?text=$query"))) return

        val anchor = activity(context)?.findViewById<View>(android.R.id.content)
        if (anchor != null && anchor.isAttachedToWindow) {
            Snackbar.make(anchor, R.string.details_maps_unavailable, Snackbar.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, R.string.details_maps_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun launch(context: Context, uri: Uri): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (activity(context) == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        // An installed handler may still be inaccessible or blocked by device policy.
        false
    }

    private fun activity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        return current as? Activity
    }
}
