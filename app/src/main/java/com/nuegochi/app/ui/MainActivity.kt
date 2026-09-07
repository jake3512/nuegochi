package com.nuegochi.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nuegochi.app.R
import com.nuegochi.app.data.PetRepository
import com.nuegochi.app.data.PetStage
import com.nuegochi.app.data.PetStats
import com.nuegochi.app.databinding.ActivityMainBinding
import com.nuegochi.app.overlay.PetOverlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: PetRepository

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) enableOverlay()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = PetRepository.get(this)

        binding.createPetButton.setOnClickListener {
            startActivity(Intent(this, PetCreatorActivity::class.java))
        }
        binding.renameButton.setOnClickListener { showRenameDialog() }
        binding.feedButton.setOnClickListener { repository.feed() }
        binding.waterButton.setOnClickListener { repository.giveWater() }
        binding.playButton.setOnClickListener { repository.play() }
        binding.cleanButton.setOnClickListener { repository.cleanPoop() }
        binding.washButton.setOnClickListener { repository.wash() }
        binding.newPetButton.setOnClickListener { startNewPetFlow() }
        binding.overlaySwitch.setOnCheckedChangeListener { switchView, checked ->
            if (!switchView.isPressed) return@setOnCheckedChangeListener
            if (checked) requestOverlayThenEnable() else disableOverlay()
        }

        binding.statHunger.statLabel.text = getString(R.string.stat_hunger)
        binding.statThirst.statLabel.text = getString(R.string.stat_thirst)
        binding.statHappiness.statLabel.text = getString(R.string.stat_happiness)
        binding.statHygiene.statLabel.text = getString(R.string.stat_hygiene)
        binding.statHunger.statBar.progressTintList = ContextCompat.getColorStateList(this, R.color.stat_hunger)
        binding.statThirst.statBar.progressTintList = ContextCompat.getColorStateList(this, R.color.stat_thirst)
        binding.statHappiness.statBar.progressTintList = ContextCompat.getColorStateList(this, R.color.stat_happiness)
        binding.statHygiene.statBar.progressTintList = ContextCompat.getColorStateList(this, R.color.stat_hygiene)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.tick()
                launch { repository.statsFlow.collect { render(it) } }
                launch {
                    while (isActive) {
                        delay(5000)
                        repository.tick()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.overlaySwitch.isChecked = repository.isOverlayEnabled() && hasOverlayPermission()
        if (repository.hasPet()) binding.previewPet.applyAppearance(repository.currentAppearance())
    }

    private fun render(stats: PetStats) {
        val hasPet = repository.hasPet()
        binding.welcomeGroup.visibility = if (hasPet) View.GONE else View.VISIBLE
        binding.dashboardGroup.visibility = if (hasPet) View.VISIBLE else View.GONE
        if (!hasPet) return

        binding.petNameStage.text = getString(R.string.pet_name_stage_format, stats.name, stats.stage.label)
        binding.previewPet.applyStats(stats)

        if (stats.poopCount > 0) {
            binding.poopIndicator.visibility = View.VISIBLE
            binding.poopIndicator.text = getString(R.string.poop_indicator_format, stats.poopCount)
        } else {
            binding.poopIndicator.visibility = View.GONE
        }

        bindStat(binding.statHunger.statValue, binding.statHunger.statBar, stats.hunger)
        bindStat(binding.statThirst.statValue, binding.statThirst.statBar, stats.thirst)
        bindStat(binding.statHappiness.statValue, binding.statHappiness.statBar, stats.happiness)
        bindStat(binding.statHygiene.statValue, binding.statHygiene.statBar, stats.hygiene)

        val isCocoon = stats.stage == PetStage.COCOON
        binding.growthLabel.visibility = if (isCocoon) View.GONE else View.VISIBLE
        binding.growthBar.visibility = if (isCocoon) View.GONE else View.VISIBLE
        if (!isCocoon) {
            binding.growthLabel.text = getString(R.string.growth_label_format, stats.expIntoStage, stats.expNeededForStage)
            binding.growthBar.max = stats.expNeededForStage
            binding.growthBar.progress = stats.expIntoStage.coerceIn(0, stats.expNeededForStage)
        }

        binding.endingBanner.visibility = if (isCocoon) View.VISIBLE else View.GONE
        if (isCocoon) {
            binding.endingBannerText.text = getString(R.string.ending_banner_format, stats.name)
        }
        val actionsEnabled = stats.stage != PetStage.EGG && !isCocoon
        listOf(binding.feedButton, binding.waterButton, binding.playButton, binding.washButton).forEach {
            it.isEnabled = actionsEnabled
        }
        binding.cleanButton.isEnabled = actionsEnabled && stats.poopCount > 0
    }

    private fun bindStat(valueView: TextView, bar: ProgressBar, value: Int) {
        valueView.text = value.toString()
        bar.progress = value
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(repository.currentStats().name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ -> repository.renamePet(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startNewPetFlow() {
        stopService(Intent(this, PetOverlayService::class.java))
        repository.setOverlayEnabled(false)
        startActivity(Intent(this, PetCreatorActivity::class.java))
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayThenEnable() {
        if (hasOverlayPermission()) {
            enableOverlay()
        } else {
            binding.overlaySwitch.isChecked = false
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun enableOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        repository.setOverlayEnabled(true)
        binding.overlaySwitch.isChecked = true
        ContextCompat.startForegroundService(this, Intent(this, PetOverlayService::class.java))
    }

    private fun disableOverlay() {
        repository.setOverlayEnabled(false)
        stopService(Intent(this, PetOverlayService::class.java))
    }
}
