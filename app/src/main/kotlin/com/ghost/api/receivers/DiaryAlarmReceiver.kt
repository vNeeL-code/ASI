package com.ghost.api.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import com.ghost.api.GemmaService

class DiaryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.ghost.api.ACTION_DIARY_CYCLE") {
            Timber.i("📔 Diary cron triggered by AlarmManager")
            GemmaService.instance?.startDiaryCycle()
        }
    }
}
