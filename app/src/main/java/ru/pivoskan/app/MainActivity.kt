package ru.pivoskan.app

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import ru.pivoskan.app.databinding.ActivityMainBinding

/**
 * Placeholder launcher screen.
 *
 * Task 3 replaces the launcher with the city picker and Task 5 rebuilds this
 * screen around the offer list.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
