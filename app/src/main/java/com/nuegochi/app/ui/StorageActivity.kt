package com.nuegochi.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nuegochi.app.R
import com.nuegochi.app.data.PetRepository
import com.nuegochi.app.data.PetStage
import com.nuegochi.app.databinding.ActivityStorageBinding
import com.nuegochi.app.render.PetView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An exhibit hall of every past pet that finished growing all the way into a cocoon. */
class StorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageBinding
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeButton.setOnClickListener { finish() }

        val completed = PetRepository.get(this).completedPets()
        binding.emptyLabel.visibility = if (completed.isEmpty()) View.VISIBLE else View.GONE
        completed.forEach { entry -> binding.storageList.addView(buildCard(entry)) }
    }

    private fun buildCard(entry: PetRepository.CompletedPet): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@StorageActivity, R.drawable.bg_canvas)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val petView = PetView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            stage = PetStage.COCOON
            applyAppearance(entry.appearance)
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(14) }
        }
        val nameText = TextView(this).apply {
            text = entry.name
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@StorageActivity, R.color.text_primary))
        }
        val dateText = TextView(this).apply {
            text = getString(R.string.storage_completed_format, dateFormat.format(Date(entry.completedAtMillis)))
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@StorageActivity, R.color.text_secondary))
        }
        textColumn.addView(nameText)
        textColumn.addView(dateText)

        row.addView(petView)
        row.addView(textColumn)
        return row
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
