package com.nuegochi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class NuegochiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "누에고치 반려동물",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "화면 위에 떠 있는 반려동물 표시 상태 알림"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val OVERLAY_CHANNEL_ID = "nuegochi_overlay"
    }
}
