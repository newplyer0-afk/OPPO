package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.TaskDatabase
import com.example.data.Task
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimetableForegroundService : Service() {

    private val CHANNEL_ID = "timeflow_channel_id"
    private var mediaPlayer: MediaPlayer? = null
    private var activeTimer: Timer? = null

    companion object {
        private var countdownJob: kotlinx.coroutines.Job? = null
        private var persistentJob: kotlinx.coroutines.Job? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("TimeFlow", "TimetableForegroundService v1.9.8 onCreate")
        createNotificationChannel()
        val initialNotif = getInitialNotification()
        startForeground(9999, initialNotif)
        startBackgroundGpsSync()
    }

    private fun getInitialNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            9998,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "timeflow_persistent_channel")
            .setContentTitle("TimeFlow Tracker")
            .setContentText("Tracking scheduled routines...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_START_PERSISTENT_NOTIF") {
            Log.d("TimetableForegroundService", "ACTION_START_PERSISTENT_NOTIF received (disabled).")
            return START_STICKY
        }
        val taskId = intent?.getIntExtra("taskId", 0) ?: 0
        val isStart = intent?.getBooleanExtra("isStart", true) ?: true
        val isFiveMinBeforeEnd = intent?.getBooleanExtra("isFiveMinBeforeEnd", false) ?: false

        // 1. ACTION_START_TASK: Tapping "Start" on initial notification
        if (intent?.action == "ACTION_START_TASK") {
            Log.d("TimetableForegroundService", "ACTION_START_TASK dismissed sound loop for taskId: $taskId")
            stopAlarmSound()
            
            val isUrdu = getSharedPreferences("timeflow_preferences", Context.MODE_PRIVATE).getString("app_language", "English") == "Urdu"
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(1001) // Cancel the original alert notification
            
            val tName = intent.getStringExtra("taskName") ?: "Task"
            val replacementIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                taskId * 10 + 5,
                replacementIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            val titleText = if (isUrdu) "انتباہ: ٹاسک شروع" else "Task Started!"
            val messageText = if (isUrdu) "ٹاسک '$tName' اب فعال ہے۔" else "Task '$tName' is now active."
            
            val replacementNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(titleText)
                .setContentText(messageText)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setOngoing(false) // swipeable!
                .build()
            
            manager.notify(1001, replacementNotification)
            stopSelf() // Stop service so "TimeFlow Tracker" ongoing notification goes away!
            return START_NOT_STICKY
        }

        // 2. ACTION_MUTE: Global Audio toggled OFF, mute immediately
        if (intent?.action == "ACTION_MUTE") {
            Log.d("TimetableForegroundService", "ACTION_MUTE received. Muting active alarm immediately.")
            stopAlarmSound()
            stopSelf()
            return START_NOT_STICKY
        }

        // 3. ACTION_CANCEL_WARN: Cancel active countdown and notifications on completed task
        if (intent?.action == "ACTION_CANCEL_WARN") {
            Log.d("TimetableForegroundService", "ACTION_CANCEL_WARN received. Cancelling countdown.")
            countdownJob?.cancel()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(1003)
            stopSelf()
            return START_STICKY
        }

        // 4. Silence Requirement: At the exact "Task End" time, there must be NO alarms and NO notifications.
        if (!isStart && !isFiveMinBeforeEnd) {
            Log.d("TimetableForegroundService", "Exact task end reached. App must remain silent.")
            stopAlarmSound()
            countdownJob?.cancel()
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(1001)
            manager.cancel(1003)
            stopSelf()
            return START_STICKY
        }

        val taskName = intent?.getStringExtra("taskName") ?: "Task"
        val isPermanent = intent?.getBooleanExtra("isPermanent", false) ?: false
        val isImportant = intent?.getBooleanExtra("isImportant", false) ?: false

        val prefs = getSharedPreferences("timeflow_preferences", Context.MODE_PRIVATE)
        val isUrdu = prefs.getBoolean("is_urdu_loyal", false)
        
        // Volume calculations: support scaling up to 200%
        val isGlobalSoundEnabled = prefs.getBoolean("is_global_sound_enabled", true)
        val appVolPercent = prefs.getFloat("app_volume_percent", 100f)
        val finalVolumeScale = if (isGlobalSoundEnabled) (appVolPercent / 100f) else 0f

        val (uriStr, fallbackKey) = if (isFiveMinBeforeEnd) {
            when {
                isImportant -> (prefs.getString("notification_important_uri", "") ?: "") to "notification_important"
                isPermanent -> (prefs.getString("notification_permanent_uri", "") ?: "") to "notification_permanent"
                else -> (prefs.getString("notification_normal_uri", "") ?: "") to "notification_normal"
            }
        } else {
            when {
                isImportant -> (prefs.getString("alarm_important_uri", "") ?: "") to "alarm_important"
                isPermanent -> (prefs.getString("alarm_permanent_uri", "") ?: "") to "alarm_permanent"
                else -> (prefs.getString("alarm_normal_uri", "") ?: "") to "alarm_normal"
            }
        }

        val title = if (isFiveMinBeforeEnd) {
            if (isUrdu) "ٹاسک کی رپورٹ" else "Task Warning & Status"
        } else {
            if (isUrdu) "ٹاسک شروع ہو گیا ہے!" else "Task Started!"
        }

        val message = if (isFiveMinBeforeEnd) {
            if (isUrdu) "رپورٹ کا معائنہ ہو رہا ہے..." else "Inspecting status report..."
        } else {
            if (isUrdu) "فوری توجہ فرماویں: ٹاسک '$taskName' کا وقت شروع ہوا ہے۔" else "Attention: Task '$taskName' has started."
        }

        // Display Active Notification
        updateForegroundNotification(taskId, title, message, isFiveMinBeforeEnd, taskName)

        // Asynchronous verification of task completed status
        val db = TaskDatabase.getDatabase(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            val task = db.taskDao().getTaskById(taskId)
            
            // Eliminate any Warning triggers of completed tasks
            if (task != null && task.isCompleted) {
                Log.d("TimetableForegroundService", "Task is already completed. Silencing entirely.")
                CoroutineScope(Dispatchers.Main).launch {
                    stopAlarmSound()
                    countdownJob?.cancel()
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(1001)
                    manager.cancel(1003)
                }
                return@launch
            }

            // Play warning or start sound
            CoroutineScope(Dispatchers.Main).launch {
                playAlarmSound(uriStr, fallbackKey, finalVolumeScale, !isFiveMinBeforeEnd)
            }

            // Start persistent warning countdown if 5-min before end
            if (isFiveMinBeforeEnd && task != null) {
                val endMin = timeSourceToMinutes(task.endTime)
                val calEnd = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, endMin / 60)
                    set(java.util.Calendar.MINUTE, endMin % 60)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                var targetTimeMs = calEnd.timeInMillis
                if (targetTimeMs <= System.currentTimeMillis()) {
                    targetTimeMs = System.currentTimeMillis() + 5 * 60 * 1000
                }

                countdownJob?.cancel()
                countdownJob = CoroutineScope(Dispatchers.Main).launch {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    
                    while (true) {
                        val currentTask = db.taskDao().getTaskById(taskId)
                        if (currentTask == null || currentTask.isCompleted) {
                            Log.d("TimetableForegroundService", "Task completed or dismissed. Closing countdown.")
                            manager.cancel(1003)
                            break
                        }
                        
                        val now = System.currentTimeMillis()
                        val diffMs = targetTimeMs - now
                        if (diffMs <= 0) {
                            Log.d("TimetableForegroundService", "Target warning elapsed.")
                            manager.cancel(1003)
                            break
                        }
                        
                        val leftSec = (diffMs / 1000).toInt()
                        val displayTime = String.format("%02d:%02d", leftSec / 60, leftSec % 60)
                        
                        val titleText = if (isUrdu) "انتباہ: ٹاسک کا وقت ختم ہونے والا ہے!" else "WARNING: TASK ENDING SOON!"
                        val displayName = if (isUrdu && currentTask.nameUrdu.isNotBlank()) currentTask.nameUrdu else currentTask.nameEnglish
                        val descText = if (isUrdu) {
                            "ٹاسک '$displayName' - باقی وقت: $displayTime"
                        } else {
                            "Task '$displayName' - TIME REMAINING: $displayTime"
                        }
                        
                        val notificationIntent = Intent(applicationContext, MainActivity::class.java)
                        val pendingIntent = PendingIntent.getActivity(
                            applicationContext,
                            taskId * 10 + 6,
                            notificationIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        
                        val warningNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                            .setContentTitle(titleText)
                            .setContentText(descText)
                            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                            .setContentIntent(pendingIntent)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(false) // swipeable!
                            .setAutoCancel(false)
                            .build()
                            
                        manager.notify(1003, warningNotification)
                        kotlinx.coroutines.delay(1000)
                    }
                    
                }
            }
        }

        // F. Safety auto-dismiss for sounds: 5s warning play cutoff, 2m main alarm play cutoff.
        val safetyDurationMs = if (isFiveMinBeforeEnd) 5000L else 120000L
        activeTimer?.cancel()
        activeTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    Log.d("TimetableForegroundService", "Safety timer cutoff triggered. Autostopping audio play.")
                    stopAlarmSound()
                }
            }, safetyDurationMs)
        }

        return START_NOT_STICKY
    }

    private fun updateForegroundNotification(taskId: Int, title: String, text: String, isNotification: Boolean, taskName: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            taskId * 10 + 3,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(!isNotification)
            .setAutoCancel(isNotification)

        if (!isNotification) {
            val startIntent = Intent(this, TimetableForegroundService::class.java).apply {
                action = "ACTION_START_TASK"
                putExtra("taskId", taskId)
                putExtra("taskName", taskName)
            }
            val startPendingIntent = PendingIntent.getService(
                this,
                taskId * 10 + 4,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Start", startPendingIntent)
        }

        val notification = builder.build()
        if (!isNotification) {
            notification.flags = notification.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, notification)
    }

    private fun getFallbackUri(context: Context, key: String): Uri {
        return when (key) {
            "alarm_permanent" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            "alarm_important" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            "alarm_normal" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            "notification_permanent" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            "notification_important" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            "notification_normal" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }

    private fun playAlarmSound(uriStr: String, fallbackKey: String, volumeScale: Float, isLoopingVal: Boolean = true) {
        try {
            stopAlarmSound()
            val uri = if (uriStr.isNotBlank()) Uri.parse(uriStr) else getFallbackUri(this, fallbackKey)
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                isLooping = isLoopingVal
                prepare()
                
                // Adjust hardware stream volume directly to allow scaling up to 200%
                val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                // Linear hardware stream volume mapping: 0% to 200% slider maps to 0 to maxAlarmVol
                val targetAlarmVol = (maxAlarmVol * (volumeScale / 2.0f)).toInt().coerceIn(0, maxAlarmVol)
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarmVol, 0)
                } catch (ex: Exception) {
                    Log.e("TimetableForegroundService", "Failed to set STREAM_ALARM volume", ex)
                }

                val directVol = volumeScale.coerceIn(0.0f, 1.0f)
                setVolume(directVol, directVol)
                start()
            }
            Log.d("TimetableForegroundService", "Sound playing successfully. URI: $uri, VolumeScale: $volumeScale")
        } catch (e: Exception) {
            Log.e("TimetableForegroundService", "MediaPlayer failed", e)
        }
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("TimetableForegroundService", "Error stopping alarm sound", e)
        }
    }

    private fun timeSourceToMinutes(timeStr: String): Int {
        val clean = timeStr.trim().uppercase()
        val isPm = clean.contains("PM")
        val isAm = clean.contains("AM")
        
        val timeWithoutAmPm = clean.replace("AM", "").replace("PM", "").trim()
        val parts = timeWithoutAmPm.split(":")
        if (parts.isEmpty()) return 0
        
        var hour = parts[0].toIntOrNull() ?: 0
        val min = if (parts.size > 1) {
            parts[1].toIntOrNull() ?: 0
        } else {
            0
        }
        
        if (isPm || isAm) {
            if (isPm && hour < 12) hour += 12
            if (isAm && hour == 12) hour = 0
        }
        return hour * 60 + min
    }

    override fun onDestroy() {
        activeTimer?.cancel()
        countdownJob?.cancel()
        persistentJob?.cancel()
        stopAlarmSound()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TimeFlow Alert Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channels for TimeFlow schedule compliance and audio indicators"
                enableVibration(false)
                vibrationPattern = longArrayOf(0)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)

            val persistentChannel = NotificationChannel(
                "timeflow_persistent_channel",
                "TimeFlow Tracker Status",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Persistent live tracking status"
                enableVibration(false)
                vibrationPattern = longArrayOf(0)
                setSound(null, null)
            }
            manager.createNotificationChannel(persistentChannel)
        }
    }

    private fun startBackgroundGpsSync() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    Log.d("TimetableForegroundService", "Running background GPS Sync process (Local Offline Sync)...")
                    val prefs = getSharedPreferences("timeflow_preferences", Context.MODE_PRIVATE)
                    
                    val fajr = prefs.getString("default_fajr_start", null)
                    val dhuhr = prefs.getString("default_dhuhr_start", null)
                    val asr = prefs.getString("default_asr_start", null)
                    val maghrib = prefs.getString("default_maghrib_start", null)
                    val isha = prefs.getString("default_isha_start", null)
                    val sunrise = prefs.getString("default_morning_start", null)
                    val sunset_raw = prefs.getString("default_night_start", null)
                    
                    if (fajr != null && dhuhr != null && asr != null && maghrib != null && isha != null && sunrise != null) {
                        val db = TaskDatabase.getDatabase(applicationContext)
                        val cal = java.util.Calendar.getInstance()
                        val yr = cal.get(java.util.Calendar.YEAR)
                        val mn = cal.get(java.util.Calendar.MONTH) + 1
                        val dy = cal.get(java.util.Calendar.DAY_OF_MONTH)
                        val dateStr = String.format("%04d-%02d-%02d", yr, mn, dy)
                        
                        val currentTasks = db.taskDao().getTasksForDate(dateStr)
                        val isMuslim = prefs.getBoolean("is_muslim_mode", true)
                        
                        if (isMuslim) {
                            currentTasks.forEach { task ->
                                if (task.isFixedPrayer) {
                                    val newStart = when {
                                        task.nameEnglish.contains("Fajr", ignoreCase = true) -> fajr
                                        task.nameEnglish.contains("Dhuhr", ignoreCase = true) -> dhuhr
                                        task.nameEnglish.contains("Asr", ignoreCase = true) -> asr
                                        task.nameEnglish.contains("Maghrib", ignoreCase = true) -> maghrib
                                        task.nameEnglish.contains("Isha", ignoreCase = true) -> isha
                                        else -> null
                                    }
                                    if (newStart != null) {
                                        val duration = timeSourceToMinutes(task.endTime) - timeSourceToMinutes(task.startTime)
                                        val finalDuration = if (duration <= 0) 45 else duration
                                        val updatedTask = task.copy(
                                            startTime = newStart,
                                            endTime = addMinutesToTime(newStart, finalDuration)
                                        )
                                        db.taskDao().updateTask(updatedTask)
                                    }
                                }
                            }
                        } else {
                            currentTasks.forEach { task ->
                                val newStart = when {
                                    task.nameEnglish.contains("Good Morning", ignoreCase = true) -> sunrise
                                    task.nameEnglish.contains("Good Night", ignoreCase = true) -> sunset_raw
                                    else -> null
                                }
                                if (newStart != null) {
                                    val duration = timeSourceToMinutes(task.endTime) - timeSourceToMinutes(task.startTime)
                                    val finalDuration = if (duration <= 0) 60 else duration
                                    val updatedTask = task.copy(
                                        startTime = newStart,
                                        endTime = addMinutesToTime(newStart, finalDuration)
                                    )
                                    db.taskDao().updateTask(updatedTask)
                                }
                            }
                        }
                        Log.d("TimetableForegroundService", "Active background GPS sync completed successfully.")
                    }
                } catch (e: Exception) {
                    Log.e("TimetableForegroundService", "Error in background GPS Sync", e)
                }
                
                // Active background sync every 4 hours, offline only
                kotlinx.coroutines.delay(4 * 3600 * 1000)
            }
        }
    }

    private fun addMinutesToTime(timeStr: String, minutesToAdd: Int): String {
        try {
            val parts = timeStr.split(":")
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            var totalMins = h * 60 + m + minutesToAdd
            if (totalMins < 0) totalMins += 24 * 60
            totalMins = totalMins % (24 * 60)
            val newH = totalMins / 60
            val newM = totalMins % 60
            return String.format("%02d:%02d", newH, newM)
        } catch (e: Exception) {
            return timeStr
        }
    }

    private fun timeToSeconds(timeStr: String): Int {
        val clean = timeStr.trim().uppercase()
        val isPm = clean.contains("PM")
        val isAm = clean.contains("AM")
        
        val timeWithoutAmPm = clean.replace("AM", "").replace("PM", "").trim()
        val parts = timeWithoutAmPm.split(":")
        if (parts.isEmpty()) return 0
        
        var hour = parts[0].toIntOrNull() ?: 0
        val min = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
        val sec = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0
        
        if (isPm || isAm) {
            if (isPm && hour < 12) hour += 12
            if (isAm && hour == 12) hour = 0
        }
        return hour * 3600 + min * 60 + sec
    }

    private fun formatCountdown(secTotal: Int): String {
        if (secTotal <= 0) return "00:00"
        val h = secTotal / 3600
        val m = (secTotal % 3600) / 60
        val s = secTotal % 60
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }
}
