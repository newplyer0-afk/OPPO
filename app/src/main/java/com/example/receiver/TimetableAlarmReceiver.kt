package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.service.TimetableForegroundService

class TimetableAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getIntExtra("taskId", 0) ?: 0
        val taskName = intent?.getStringExtra("taskName") ?: "TimeFlow Task"
        val isStart = intent?.getBooleanExtra("isStart", true) ?: true
        val isFiveMinBeforeEnd = intent?.getBooleanExtra("isFiveMinBeforeEnd", false) ?: false
        val isPermanent = intent?.getBooleanExtra("isPermanent", false) ?: false
        val isImportant = intent?.getBooleanExtra("isImportant", false) ?: false
        
        Log.d("TimetableAlarmReceiver", "Alarm fired! Task: $taskName, isStart: $isStart, isFiveMinBeforeEnd: $isFiveMinBeforeEnd, isPermanent: $isPermanent, isImportant: $isImportant")

        val serviceIntent = Intent(context, TimetableForegroundService::class.java).apply {
            putExtra("taskId", taskId)
            putExtra("taskName", taskName)
            putExtra("isStart", isStart)
            putExtra("isFiveMinBeforeEnd", isFiveMinBeforeEnd)
            putExtra("isPermanent", isPermanent)
            putExtra("isImportant", isImportant)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
