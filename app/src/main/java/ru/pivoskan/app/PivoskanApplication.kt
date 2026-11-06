package ru.pivoskan.app

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import ru.pivoskan.app.data.scan.*

class PivoskanApplication : Application(), Application.ActivityLifecycleCallbacks {
    private lateinit var visibility: ScanVisibility
    override fun onCreate() {
        super.onCreate()
        val runtime = CityScanRuntime.get(this)
        visibility = ScanVisibility({ runtime.visibilityChanged(it == VisibilityTransition.VISIBLE) }, runtime::requestsAllowed)
        registerActivityLifecycleCallbacks(this)
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { visibility.screenInteractive(interactive()) }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        visibility.screenInteractive(interactive())
    }
    private fun interactive() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    override fun onActivityStarted(activity: Activity) {
        visibility.screenInteractive(interactive())
        visibility.activityStarted(System.identityHashCode(activity).toString())
    }
    override fun onActivityStopped(activity: Activity) = visibility.activityStopped(
        System.identityHashCode(activity).toString(), activity.isChangingConfigurations)
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityResumed(activity: Activity) { visibility.screenInteractive(interactive()) }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
