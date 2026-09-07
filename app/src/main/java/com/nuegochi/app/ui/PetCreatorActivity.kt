package com.nuegochi.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nuegochi.app.R
import com.nuegochi.app.data.PetPart
import com.nuegochi.app.data.PetRepository
import com.nuegochi.app.data.PetStage
import com.nuegochi.app.databinding.ActivityPetCreatorBinding
import com.nuegochi.app.draw.BitmapIO
import com.nuegochi.app.draw.GuideShape

/**
 * Walks the user through hand-drawing every body part one at a time, then lets them name
 * the finished pet before handing off to [MainActivity].
 */
class PetCreatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPetCreatorBinding
    private lateinit var repository: PetRepository

    private val parts = PetPart.creationOrder
    private var partIndex = 0

    private val palette = intArrayOf(
        Color.parseColor("#1A1210"), Color.parseColor("#FFFFFF"), Color.parseColor("#F25C54"),
        Color.parseColor("#F2A65A"), Color.parseColor("#F4D35E"), Color.parseColor("#8FC93A"),
        Color.parseColor("#3EA6A0"), Color.parseColor("#3E9DE0"), Color.parseColor("#6C5CE7"),
        Color.parseColor("#EC6FBB"), Color.parseColor("#8B5E34"), Color.parseColor("#A0A0A0")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPetCreatorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = PetRepository.get(this)
        repository.prepareForNewPetCreation()

        buildPalette()
        setupBrushControls()
        setupNavigation()
        showPart(0)
    }

    private fun buildPalette() {
        binding.colorPaletteRow.removeAllViews()
        val sizePx = (36 * resources.displayMetrics.density).toInt()
        val marginPx = (6 * resources.displayMetrics.density).toInt()
        palette.forEachIndexed { index, color ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#33000000"))
                }
                setOnClickListener {
                    binding.drawingView.brushColor = color
                    binding.drawingView.eraseMode = false
                }
            }
            val params = android.widget.LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginEnd = marginPx
            }
            binding.colorPaletteRow.addView(swatch, params)
            if (index == 0) swatch.performClick()
        }
    }

    private fun setupBrushControls() {
        binding.brushThin.setOnClickListener { binding.drawingView.brushWidth = 8f }
        binding.brushMedium.setOnClickListener { binding.drawingView.brushWidth = 18f }
        binding.brushThick.setOnClickListener { binding.drawingView.brushWidth = 32f }
        binding.eraseToggle.setOnClickListener {
            binding.drawingView.eraseMode = !binding.drawingView.eraseMode
            binding.eraseToggle.alpha = if (binding.drawingView.eraseMode) 1f else 0.6f
        }
        binding.undoButton.setOnClickListener { binding.drawingView.undo() }
        binding.clearButton.setOnClickListener { binding.drawingView.clear() }
    }

    private fun setupNavigation() {
        binding.skipButton.setOnClickListener { advance(save = false) }
        binding.nextButton.setOnClickListener { advance(save = true) }
        binding.startButton.setOnClickListener { finishCreation() }
    }

    private fun showPart(index: Int) {
        val part = parts[index]
        binding.stepTitle.text = getString(
            R.string.creator_step_title, index + 1, parts.size, part.displayName
        )
        binding.drawingView.clear()
        binding.drawingView.guide = guideFor(part)
        binding.drawingView.eraseMode = false
        binding.eraseToggle.alpha = 0.6f
        binding.drawingView.brushWidth = 18f
    }

    private fun guideFor(part: PetPart): GuideShape = when (part) {
        PetPart.HEAD -> GuideShape.HEAD
        PetPart.BODY -> GuideShape.TORSO
        PetPart.ARM_LEFT, PetPart.ARM_RIGHT, PetPart.LEG_LEFT, PetPart.LEG_RIGHT -> GuideShape.LIMB
        PetPart.TAIL -> GuideShape.TAIL
    }

    private fun advance(save: Boolean) {
        val part = parts[partIndex]
        if (save && binding.drawingView.hasContent()) {
            binding.drawingView.exportBitmap()?.let { BitmapIO.save(it, repository.partFile(part)) }
        }
        if (partIndex < parts.size - 1) {
            partIndex++
            showPart(partIndex)
        } else {
            showNamingStep()
        }
    }

    private fun showNamingStep() {
        binding.drawingGroup.visibility = View.GONE
        binding.namingGroup.visibility = View.VISIBLE
        binding.previewPetView.stage = PetStage.BABY
        binding.previewPetView.loadFromRepository(repository)
    }

    private fun finishCreation() {
        if (countDrawnParts() == 0) {
            Toast.makeText(this, R.string.need_at_least_one_part, Toast.LENGTH_SHORT).show()
            return
        }
        val name = binding.nameInput.text?.toString().orEmpty()
        if (name.isBlank()) {
            Toast.makeText(this, R.string.need_a_name, Toast.LENGTH_SHORT).show()
            return
        }
        repository.finalizeNewPet(name)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun countDrawnParts(): Int = parts.count { repository.partFile(it).exists() }
}
