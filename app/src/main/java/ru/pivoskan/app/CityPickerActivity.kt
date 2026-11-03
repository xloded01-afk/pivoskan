package ru.pivoskan.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.Prefs
import ru.pivoskan.app.databinding.ActivityCityPickerBinding

/** First-run city selection and the explicit "change_city" route from the offer list. */
class CityPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCityPickerBinding
    private lateinit var prefs: Prefs
    private var selectedCityId: String? = null
    private var openingMain = false

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.themeMode)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val changingCity = intent.getBooleanExtra(EXTRA_CHANGE_CITY, false)
        val savedCity = prefs.cityId?.let(CityCatalog::city)
        // Decide before inflating: a valid stored city never flashes the picker UI.
        if (!changingCity && savedCity != null) {
            openMain()
            return
        }

        binding = ActivityCityPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeInsets()
        ViewCompat.setAccessibilityHeading(binding.pickerTitle, true)

        if (changingCity) {
            binding.pickerTitle.setText(R.string.picker_change_city_title)
            binding.findBeerButton.setText(R.string.picker_apply_city)
        }

        val cities = CityCatalog.cities
        // Geographic names are catalog data; IDs remain the persistence contract.
        val labels = cities.map { it.name }
        binding.cityDropdown.setAdapter(cityAdapter(labels))
        binding.cityDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedCityId = cities.getOrNull(position)?.id
            binding.findBeerButton.isEnabled = selectedCityId != null
        }

        val initialCityId = if (savedInstanceState != null) {
            savedInstanceState.getString(STATE_SELECTED_CITY)
        } else {
            savedCity?.id.takeIf { changingCity }
        }
        selectedCityId = initialCityId?.takeIf { CityCatalog.city(it) != null }
        selectedCityId?.let { cityId ->
            // The false argument avoids filtering away other cities after restoring a value.
            binding.cityDropdown.setText(CityCatalog.city(cityId)?.name.orEmpty(), false)
        }
        binding.findBeerButton.isEnabled = selectedCityId != null

        if (savedInstanceState != null) binding.beerGlass.finishFillAnimation()

        binding.findBeerButton.setOnClickListener {
            val city = selectedCityId?.let(CityCatalog::city) ?: return@setOnClickListener
            prefs.cityId = city.id
            openMain()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_CITY, selectedCityId)
        super.onSaveInstanceState(outState)
    }

    private fun applySafeInsets() {
        val root = binding.root
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun cityAdapter(labels: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextAppearance(R.style.TextAppearance_Pivoskan_Body)
                    setTextColor(getColor(R.color.text_primary))
                    minHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
                    val horizontal = resources.getDimensionPixelSize(R.dimen.space_lg)
                    val vertical = resources.getDimensionPixelSize(R.dimen.space_md)
                    setPaddingRelative(horizontal, vertical, horizontal, vertical)
                    // Long city names may wrap at large accessibility font scales.
                    setSingleLine(false)
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                getView(position, convertView, parent)
        }

    private fun openMain() {
        if (openingMain) return
        openingMain = true
        // CLEAR_TOP alone recreates an existing MainActivity, resetting city filters.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    companion object {
        const val EXTRA_CHANGE_CITY = "change_city"
        private const val STATE_SELECTED_CITY = "picker_selected_city"
    }
}
