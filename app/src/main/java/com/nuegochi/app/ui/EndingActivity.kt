package com.nuegochi.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nuegochi.app.R
import com.nuegochi.app.data.PetRepository
import com.nuegochi.app.data.PetStage
import com.nuegochi.app.databinding.ActivityEndingBinding

/** Shown once when the pet finishes growing and spins itself into a cocoon - the game's ending. */
class EndingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEndingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEndingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repository = PetRepository.get(this)
        val name = intent.getStringExtra(EXTRA_PET_NAME) ?: repository.currentStats().name

        binding.endingTitle.text = getString(R.string.ending_title_format, name)
        binding.endingPetView.stage = PetStage.COCOON

        binding.keepWatchingButton.setOnClickListener { finish() }
        binding.viewStorageButton.setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }
        binding.newPetButton.setOnClickListener {
            repository.prepareForNewPetCreation()
            startActivity(Intent(this, PetCreatorActivity::class.java))
            finish()
        }
    }

    companion object {
        const val EXTRA_PET_NAME = "extra_pet_name"
    }
}
