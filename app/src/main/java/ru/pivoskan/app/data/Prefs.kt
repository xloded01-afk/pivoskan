package ru.pivoskan.app.data

import android.content.Context

class Prefs(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var cityId: String?
        get() = preferences.getString(KEY_CITY, null)
        set(value) {
            preferences.edit().putString(KEY_CITY, value).apply()
        }

    /**
     * Night-mode constant. The raw AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
     * value (-1) is stored so this data class stays independent of AppCompat;
     * callers pass the value straight to AppCompatDelegate.setDefaultNightMode.
     */
    var themeMode: Int
        get() = preferences.getInt(KEY_THEME, THEME_FOLLOW_SYSTEM)
        set(value) {
            preferences.edit().putInt(KEY_THEME, value).apply()
        }

    val hasCity: Boolean
        get() = cityId != null

    fun magnitShopId(cityId: String): String? = preferences.getString("magnit_shop_$cityId", null)
    fun setMagnitShopId(cityId: String, shopId: String) {
        preferences.edit().putString("magnit_shop_$cityId", shopId).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "pivoskan_prefs"
        const val KEY_CITY = "city_id"
        const val KEY_THEME = "theme_mode"

        /** AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM */
        const val THEME_FOLLOW_SYSTEM = -1
    }
}
