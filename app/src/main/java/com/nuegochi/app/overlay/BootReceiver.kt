package com.nuegochi.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nuegochi.app.data.PetRepository

/** Restarts the floating pet after a reboot if the user had it turned on before shutting down. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repository = PetRepository.get(context)
        if (!repository.hasPet() || !repository.isOverlayEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return
        ContextCompat.startForegroundService(context, Intent(context, PetOverlayService::class.java))
    }
}
