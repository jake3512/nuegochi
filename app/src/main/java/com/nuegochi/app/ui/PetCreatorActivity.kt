package com.nuegochi.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nuegochi.app.R
import com.nuegochi.app.data.BodyPart
import com.nuegochi.app.data.PetAppearance
import com.nuegochi.app.data.PetRepository
import com.nuegochi.app.data.PetStage
import com.nuegochi.app.databinding.ActivityPetCreatorBinding

/**
 * A one-screen stick-figure customizer: pick a body part, color it from the palette, adjust
 * arm/leg length with the sliders, then name the pet and start raising it.
 */
class PetCreatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPetCreatorBinding
    private lateinit var repository: PetRepository

    private var appearance = PetAppearance.default()
    private var selectedPart = BodyPart.HEAD
    private val partChips = mutableMapOf<BodyPart, Button>()

    private val palette = intArrayOf(
        Color.parseColor("#4A3728"), Color.parseColor("#1A1210"), Color.parseColor("#FFFFFF"),
        Color.parseColor("#F25C54"), Color.parseColor("#F2A65A"), Color.parseColor("#F4D35E"),
        Color.parseColor("#8FC93A"), Color.parseColor("#3EA6A0"), Color.parseColor("#3E9DE0"),
        Color.parseColor("#6C5CE7"), Color.parseColor("#EC6FBB"), Color.parseColor("#A0A0A0")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPetCreatorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = PetRepository.get(this)
        repository.prepareForNewPetCreation()

        binding.previewPetView.stage = PetStage.BABY
        buildPartSelector()
        buildPalette()
        setupLengthSliders()
        refreshPreview()

        binding.startButton.setOnClickListener { finishCreation() }
    }

    private fun buildPartSelector() {
        binding.partSelectorRow.removeAllViews()
        val marginPx = (6 * resources.displayMetrics.density).toInt()
        BodyPart.entries.forEach { part ->
            val chip = Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = part.displayName
                setAllCaps(false)
                alpha = if (part == selectedPart) 1f else 0.55f
                setOnClickListener { selectPart(part) }
            }
            partChips[part] = chip
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = marginPx }
            binding.partSelectorRow.addView(chip, params)
        }
    }

    private fun selectPart(part: BodyPart) {
        selectedPart = part
        partChips.forEach { (p, chip) -> chip.alpha = if (p == part) 1f else 0.55f }
    }

    private fun buildPalette() {
        binding.colorPaletteRow.removeAllViews()
        val sizePx = (36 * resources.displayMetrics.density).toInt()
        val marginPx = (6 * resources.displayMetrics.density).toInt()
        palette.forEach { color ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#33000000"))
                }
                setOnClickListener {
                    appearance = withColor(appearance, selectedPart, color)
                    refreshPreview()
                }
            }
            val params = LinearLayout.LayoutParams(sizePx, sizePx).apply { marginEnd = marginPx }
            binding.colorPaletteRow.addView(swatch, params)
        }
    }

    private fun setupLengthSliders() {
        binding.armLengthLabel.text = getString(R.string.length_label_format, getString(R.string.part_arms))
        binding.legLengthLabel.text = getString(R.string.length_label_format, getString(R.string.part_legs))

        binding.armLengthSeek.progress = lengthToProgress(appearance.armLength)
        binding.legLengthSeek.progress = lengthToProgress(appearance.legLength)

        binding.armLengthSeek.setOnSeekBarChangeListener(
            onLengthChange { appearance = appearance.copy(armLength = it) }
        )
        binding.legLengthSeek.setOnSeekBarChangeListener(
            onLengthChange { appearance = appearance.copy(legLength = it) }
        )
    }

    private fun onLengthChange(apply: (Float) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            apply(progressToLength(progress))
            refreshPreview()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    private fun progressToLength(progress: Int): Float {
        val range = PetAppearance.MAX_LIMB_LENGTH - PetAppearance.MIN_LIMB_LENGTH
        return PetAppearance.MIN_LIMB_LENGTH + range * (progress / 100f)
    }

    private fun lengthToProgress(length: Float): Int {
        val range = PetAppearance.MAX_LIMB_LENGTH - PetAppearance.MIN_LIMB_LENGTH
        return (((length - PetAppearance.MIN_LIMB_LENGTH) / range) * 100f).toInt().coerceIn(0, 100)
    }

    private fun withColor(base: PetAppearance, part: BodyPart, color: Int): PetAppearance = when (part) {
        BodyPart.HEAD -> base.copy(headColor = color)
        BodyPart.BODY -> base.copy(bodyColor = color)
        BodyPart.ARMS -> base.copy(armColor = color)
        BodyPart.LEGS -> base.copy(legColor = color)
    }

    private fun refreshPreview() {
        binding.previewPetView.applyAppearance(appearance)
    }

    private fun finishCreation() {
        val name = binding.nameInput.text?.toString().orEmpty()
        if (name.isBlank()) {
            Toast.makeText(this, R.string.need_a_name, Toast.LENGTH_SHORT).show()
            return
        }
        repository.saveAppearance(appearance)
        repository.finalizeNewPet(name)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
