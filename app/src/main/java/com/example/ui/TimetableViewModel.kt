package com.example.ui

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Task
import com.example.data.TaskDatabase
import com.example.data.TaskRepository
import com.example.receiver.TimetableAlarmReceiver
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimetableViewModel(application: Application) : AndroidViewModel(application) {
    private val initializationMutex = Mutex()
    private val initializedDates = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val repository: TaskRepository = TaskRepository(TaskDatabase.getDatabase(application).taskDao())

    val allHistoryTasks: StateFlow<List<Task>> = repository.getAllTasksFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val prefs = application.getSharedPreferences("timeflow_preferences", Context.MODE_PRIVATE)

    private val _currentMinutes = MutableStateFlow(getRealCurrentMinutes())
    val currentMinutes: StateFlow<Int> = _currentMinutes.asStateFlow()

    private val _isSimulatedTime = MutableStateFlow(false)
    val isSimulatedTime: StateFlow<Boolean> = _isSimulatedTime.asStateFlow()

    // Global toggle for language support
    val isUrduLoyal = MutableStateFlow(prefs.getBoolean("is_urdu_loyal", false))

    // Current selected date string (YYYY-MM-DD)
    val selectedDate = MutableStateFlow(getTodayDateString())

    // 40-day historical score
    val monthlyPerformancePercent = MutableStateFlow(0)
    val monthlyTitle = MutableStateFlow("Serious") // "Lazy", "Serious", "Elite"
    val showMonthlyReport = MutableStateFlow(false)
    val totalTrackedDays = MutableStateFlow(0)

    // Form inputs state
    val taskNameInput = MutableStateFlow("")
    val startTimeInput = MutableStateFlow("09:00")
    val endTimeInput = MutableStateFlow("10:00")
    val isTaskInputPermanent = MutableStateFlow(false)
    val isDialogUrduMode = MutableStateFlow(true) // independent task input translation assistance
    val isTaskImportantInput = MutableStateFlow(false) // Smart important checking

    // Date navigation logged dates list
    val loggedDates = MutableStateFlow<List<String>>(emptyList())

    // Advanced schema selections
    val selectedWeekDays = MutableStateFlow(listOf(true, true, true, true, true, true, true))
    val isTaskLockedInput = MutableStateFlow(true)

    // Settings
    val isMuslimMode = MutableStateFlow(prefs.getBoolean("is_muslim_mode", true))
    val currentLanguage = MutableStateFlow(prefs.getString("current_language", "English") ?: "English")
    
    // 6-Channel sound settings
    val alarmPermanentUri = MutableStateFlow(prefs.getString("alarm_permanent_uri", "") ?: "")
    val alarmImportantUri = MutableStateFlow(prefs.getString("alarm_important_uri", "") ?: "")
    val alarmNormalUri = MutableStateFlow(prefs.getString("alarm_normal_uri", "") ?: "")
    val notificationPermanentUri = MutableStateFlow(prefs.getString("notification_permanent_uri", "") ?: "")
    val notificationImportantUri = MutableStateFlow(prefs.getString("notification_important_uri", "") ?: "")
    val notificationNormalUri = MutableStateFlow(prefs.getString("notification_normal_uri", "") ?: "")
    val appVolume = MutableStateFlow(prefs.getFloat("app_volume_percent", prefs.getFloat("app_volume", 1.0f) * 100f))
    val isGlobalSoundEnabled = MutableStateFlow(prefs.getBoolean("is_global_sound_enabled", true))
    val isPreviewPlaying = MutableStateFlow<String?>(null)

    // Real-time ticking clock flow HH:MM:SS
    val realTimeClockStr: StateFlow<String> = flow {
        while (true) {
            val cal = Calendar.getInstance()
            emit(String.format("%02d:%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)))
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "00:00:00")

    init {
        android.util.Log.i("TimeFlow", "TimeFlow Tracker v1.9.8 Initialized")
        updateHasSavedDataFlag()

        // Periodically sync or tick validation rules
        viewModelScope.launch {
            while (true) {
                if (!_isSimulatedTime.value) {
                    val now = getRealCurrentMinutes()
                    if (_currentMinutes.value != now) {
                        _currentMinutes.value = now
                        validateLiveFailures()
                    }
                } else {
                    validateLiveFailures()
                }
                delay(3000)
            }
        }

        // Initialize today immediately
        viewModelScope.launch {
            initializeDayIfEmpty(getTodayDateString())
            calculateMonthlyPerformanceTitle()
        }

        // Keep 40-day title and logged dates calculated dynamically on task updates
        viewModelScope.launch {
            repository.getAllTasksFlow().collect { allTasks ->
                calculateMonthlyPerformanceTitle()
                val dates = allTasks.map { it.dateString }.distinct().sorted()
                loggedDates.value = dates
            }
        }
    }

    // Expose flows for current selected day's tasks
    val tasks: StateFlow<List<Task>> = selectedDate
        .flatMapLatest { date ->
            // Ensure day exists when selected
            viewModelScope.launch {
                initializeDayIfEmpty(date)
            }
            repository.getTasksForDateFlow(date)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun navigateToPreviousLoggedDate() {
        val current = selectedDate.value
        val sortedDates = loggedDates.value.filter { it < current }.sorted()
        if (sortedDates.isNotEmpty()) {
            selectedDate.value = sortedDates.last()
        }
    }

    fun navigateToNextLoggedDate() {
        val current = selectedDate.value
        val today = getTodayDateString()
        val sortedDates = (loggedDates.value + today).distinct().filter { it > current && it <= today }.sorted()
        if (sortedDates.isNotEmpty()) {
            selectedDate.value = sortedDates.first()
        }
    }

    fun changeDateOffset(days: Int) {
        if (days < 0) navigateToPreviousLoggedDate() else navigateToNextLoggedDate()
    }

    fun getDayOfWeekIndex(dateStr: String): Int {
        try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val date = format.parse(dateStr) ?: return 0
            val cal = Calendar.getInstance()
            cal.time = date
            val day = cal.get(Calendar.DAY_OF_WEEK)
            return when (day) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                Calendar.SUNDAY -> 6
                else -> 0
            }
        } catch (e: Exception) {
            return 0
        }
    }

    suspend fun initializeDayIfEmpty(dateStr: String) {
        if (initializedDates.contains(dateStr)) return
        initializationMutex.withLock {
            if (initializedDates.contains(dateStr)) return
            val existing = repository.getTasksForDate(dateStr)
            if (existing.isEmpty()) {
                val allInDb = repository.getAllTasks()
                
                // Extract permanent unique tasks created historically
                val permanentTemplates = allInDb
                    .filter { it.isPermanent }
                    .distinctBy { it.nameEnglish.lowercase() }

                val isMuslim = prefs.getBoolean("is_muslim_mode", true)
                if (isMuslim) {
                    // Standardize 5 mandatory daily prayers with fixed initial times
                    val prayers = listOf(
                        Task(nameEnglish = "Fajr Prayer", nameUrdu = "فجر کی نماز", startTime = "05:00", endTime = "05:45", isFixedPrayer = true, dateString = dateStr),
                        Task(nameEnglish = "Dhuhr Prayer", nameUrdu = "ظہر کی نماز", startTime = "13:00", endTime = "13:45", isFixedPrayer = true, dateString = dateStr),
                        Task(nameEnglish = "Asr Prayer", nameUrdu = "عصر کی نماز", startTime = "16:30", endTime = "17:15", isFixedPrayer = true, dateString = dateStr),
                        Task(nameEnglish = "Maghrib Prayer", nameUrdu = "مغرب کی نماز", startTime = "19:15", endTime = "19:45", isFixedPrayer = true, dateString = dateStr),
                        Task(nameEnglish = "Isha Prayer", nameUrdu = "عشاء کی نماز", startTime = "21:00", endTime = "21:45", isFixedPrayer = true, dateString = dateStr)
                    )
                    prayers.forEach { repository.insert(it) }
                } else {
                    // Non-Muslim Mode: Exactly two baseline, fully customizable default task anchors
                    val anchors = listOf(
                        Task(nameEnglish = "Good Morning", nameUrdu = "صبح بخیر", startTime = "07:00", endTime = "08:00", isFixedPrayer = false, isPermanent = true, dateString = dateStr),
                        Task(nameEnglish = "Good Night", nameUrdu = "شب بخیر", startTime = "22:00", endTime = "23:00", isFixedPrayer = false, isPermanent = true, dateString = dateStr)
                    )
                    anchors.forEach { repository.insert(it) }
                }

                val dayIndex = getDayOfWeekIndex(dateStr)
                // Re-populate permanent tasks matching target weekdays
                permanentTemplates.forEach { perm ->
                    val weekdays = perm.targetWeekdays
                    val activeOnThisDay = if (weekdays.length == 7) weekdays[dayIndex] == '1' else true
                    if (activeOnThisDay) {
                        repository.insert(
                            Task(
                                nameEnglish = perm.nameEnglish,
                                nameUrdu = perm.nameUrdu,
                                startTime = perm.startTime,
                                endTime = perm.endTime,
                                isCompleted = false,
                                isFailed = false,
                                isFixedPrayer = false,
                                isPermanent = true,
                                dateString = dateStr,
                                targetWeekdays = perm.targetWeekdays,
                                isLocked = perm.isLocked
                            )
                        )
                    }
                }
            }
            initializedDates.add(dateStr)
        }
    }

    fun updateTaskName(name: String) {
        taskNameInput.value = name
    }

    fun updateStartTime(time: String) {
        startTimeInput.value = time
    }

    fun updateEndTime(time: String) {
        endTimeInput.value = time
    }

    fun initializeDefaultTaskTimes() {
        val cal = Calendar.getInstance()
        val hrStart = cal.get(Calendar.HOUR_OF_DAY)
        val minStart = cal.get(Calendar.MINUTE)
        
        cal.add(Calendar.MINUTE, 30)
        val hrEnd = cal.get(Calendar.HOUR_OF_DAY)
        val minEnd = cal.get(Calendar.MINUTE)
        
        val startStr = String.format("%02d:%02d", hrStart, minStart)
        val endStr = String.format("%02d:%02d", hrEnd, minEnd)
        
        startTimeInput.value = startStr
        endTimeInput.value = endStr
    }

    fun togglePermanentCheckbox() {
        isTaskInputPermanent.value = !isTaskInputPermanent.value
    }

    fun toggleDialogLanguage() {
        isDialogUrduMode.value = !isDialogUrduMode.value
    }

    fun toggleGlobalLanguage() {
        val newValue = !isUrduLoyal.value
        isUrduLoyal.value = newValue
        prefs.edit().putBoolean("is_urdu_loyal", newValue).apply()
    }

    fun setSimulatedTimeMinutes(minutes: Int) {
        _isSimulatedTime.value = true
        _currentMinutes.value = minutes
        viewModelScope.launch {
            validateLiveFailures()
        }
    }

    fun useRealTime() {
        _isSimulatedTime.value = false
        _currentMinutes.value = getRealCurrentMinutes()
        viewModelScope.launch {
            validateLiveFailures()
        }
    }

    private fun getRealCurrentMinutes(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    fun getTodayDateString(): String {
        val cal = Calendar.getInstance()
        return String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    fun getTodayDayOfWeek(dateStr: String): String {
        try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val date = format.parse(dateStr) ?: return "Monday"
            val cal = Calendar.getInstance()
            cal.time = date
            val day = cal.get(Calendar.DAY_OF_WEEK)
            return when (day) {
                Calendar.SUNDAY -> "Sunday"
                Calendar.MONDAY -> "Monday"
                Calendar.TUESDAY -> "Tuesday"
                Calendar.WEDNESDAY -> "Wednesday"
                Calendar.THURSDAY -> "Thursday"
                Calendar.FRIDAY -> "Friday"
                Calendar.SATURDAY -> "Saturday"
                else -> "Monday"
            }
        } catch (e: Exception) {
            return "Today"
        }
    }

    fun isTaskSubmittable(task: Task, nowMin: Int = _currentMinutes.value): Boolean {
        if (task.isCompleted) return false

        // Must match today
        val todayStr = getTodayDateString()
        if (task.dateString != todayStr) return false

        // Check window block
        if (isDayFrozen(tasks.value, task.dateString, nowMin)) return false

        val startMin = timeToMinutes(task.startTime)
        val endMin = timeToMinutes(task.endTime)
        return nowMin in startMin..endMin && !task.isFailed
    }

    fun isDayFrozen(taskList: List<Task>, dateStr: String, nowMin: Int = _currentMinutes.value): Boolean {
        if (dateStr != getTodayDateString()) return true
        if (taskList.isNotEmpty()) {
            val latestEnd = taskList.maxOfOrNull { timeToMinutes(it.endTime) } ?: 0
            if (nowMin >= latestEnd + 5) {
                return true
            }
        }
        return false
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch(Dispatchers.IO) {
            val nowMin = _currentMinutes.value
            if (isTaskSubmittable(task, nowMin) && !task.isCompleted) {
                val updated = task.copy(isCompleted = true)
                repository.update(updated)
                sendCompletionNotification(updated)
            }
        }
    }

    private fun sendCompletionNotification(task: Task) {
        val context = getApplication<Application>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        // Remove 5-minute warning notification and start/end alarms
        manager.cancel(1001)
        manager.cancel(1003)
        
        try {
            val cancelIntent = Intent(context, com.example.service.TimetableForegroundService::class.java).apply {
                action = "ACTION_CANCEL_WARN"
                putExtra("taskId", task.id)
            }
            context.startService(cancelIntent)
        } catch (e: Exception) {
            Log.e("TimetableViewModel", "Error sending ACTION_CANCEL_WARN", e)
        }
        
        val channelId = "timeflow_completion_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "TimeFlow Completion"
            val importance = android.app.NotificationManager.IMPORTANCE_HIGH
            val channel = android.app.NotificationChannel(channelId, name, importance).apply {
                description = "Task completion notifications"
            }
            manager.createNotificationChannel(channel)
        }
        
        val intent = Intent(context, com.example.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            task.id * 10 + 5,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val isUrdu = prefs.getBoolean("is_urdu_loyal", true)
        val text = if (isUrdu) "درجہ: ٹاسک کامیابی سے مکمل ہوا" else "Status: Task completed successfully."
        
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setContentTitle("TimeFlow Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false) // swipeable
            
        manager.notify(task.id + 10000, builder.build())
    }

    fun addNewTask(context: Context) {
        val name = taskNameInput.value.trim()
        val start = startTimeInput.value.trim()
        val end = endTimeInput.value.trim()
        val isPerm = isTaskInputPermanent.value
        val isInputUrdu = isDialogUrduMode.value

        if (name.isEmpty() || start.isEmpty() || end.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val weekdaysStr = selectedWeekDays.value.map { if (it) '1' else '0' }.joinToString("")
            val task = Task(
                nameEnglish = if (isInputUrdu) "" else name,
                nameUrdu = if (isInputUrdu) name else "",
                startTime = start,
                endTime = end,
                isCompleted = false,
                isFailed = false,
                isFixedPrayer = false,
                isPermanent = isPerm,
                dateString = selectedDate.value,
                targetWeekdays = weekdaysStr,
                isLocked = isTaskLockedInput.value,
                isImportant = isTaskImportantInput.value
            )
            repository.insert(task)
            
            // Clean inputs
            taskNameInput.value = ""
            isTaskInputPermanent.value = false
            selectedWeekDays.value = listOf(true, true, true, true, true, true, true)
            isTaskLockedInput.value = true
            isTaskImportantInput.value = false

            // Reschedule alarms for compliance
            scheduleDailyAlarms(context)
        }
    }

    fun editPrayerTimes(task: Task, newStart: String, newEnd: String, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = task.copy(startTime = newStart, endTime = newEnd)
            repository.update(updated)
            scheduleDailyAlarms(context)
        }
    }

    fun unlockTask(task: Task) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = task.copy(isLocked = false)
            repository.update(updated)
        }
    }

    fun toggleSafeMode(task: Task) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = task.copy(isLocked = !task.isLocked)
            repository.update(updated)
        }
    }

    fun updateTask(task: Task, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(task)
            scheduleDailyAlarms(context)
        }
    }

    fun deleteTask(task: Task) {
        if (task.dateString != getTodayDateString()) return // lock deletion edits on history days
        if (task.isFixedPrayer) return // locked prayer check
        if (task.isCompleted) return // Anti-cheating lock!

        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(task)
        }
    }

    fun toggleWeekdaySelection(index: Int) {
        val current = selectedWeekDays.value.toMutableList()
        current[index] = !current[index]
        selectedWeekDays.value = current
    }

    fun toggleMuslimMode(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val newValue = !isMuslimMode.value
            prefs.edit().putBoolean("is_muslim_mode", newValue).apply()
            isMuslimMode.value = newValue
            
            if (!newValue) {
                // Non-Muslim Mode: immediately drop all daily prayer entities
                repository.deleteFixedPrayers()
                
                // Replace with Good Morning and Good Night for today
                val today = getTodayDateString()
                val currentTasks = repository.getTasksForDate(today)
                val anchorAdded = currentTasks.any { it.nameEnglish == "Good Morning" || it.nameEnglish == "Good Night" }
                if (!anchorAdded) {
                    val morningAnchor = Task(
                        nameEnglish = "Good Morning", nameUrdu = "صبح بخیر",
                        startTime = "07:00", endTime = "08:00",
                        isCompleted = false, isFailed = false,
                        isFixedPrayer = false, isPermanent = true,
                        dateString = today
                    )
                    val nightAnchor = Task(
                        nameEnglish = "Good Night", nameUrdu = "شب بخیر",
                        startTime = "22:00", endTime = "23:00",
                        isCompleted = false, isFailed = false,
                        isFixedPrayer = false, isPermanent = true,
                        dateString = today
                    )
                    repository.insert(morningAnchor)
                    repository.insert(nightAnchor)
                }
            } else {
                // Re-add standard islamic prayers for today if missing
                val today = getTodayDateString()
                val currentTasks = repository.getTasksForDate(today)
                val hasPrayers = currentTasks.any { it.isFixedPrayer }
                if (!hasPrayers) {
                    val prayers = listOf(
                        Task(nameEnglish = "Fajr Prayer", nameUrdu = "فجر کی نماز", startTime = "05:00", endTime = "05:45", isFixedPrayer = true, dateString = today),
                        Task(nameEnglish = "Dhuhr Prayer", nameUrdu = "ظہر کی نماز", startTime = "13:00", endTime = "13:45", isFixedPrayer = true, dateString = today),
                        Task(nameEnglish = "Asr Prayer", nameUrdu = "عصر کی نماز", startTime = "16:30", endTime = "17:15", isFixedPrayer = true, dateString = today),
                        Task(nameEnglish = "Maghrib Prayer", nameUrdu = "مغرب کی نماز", startTime = "19:15", endTime = "19:45", isFixedPrayer = true, dateString = today),
                        Task(nameEnglish = "Isha Prayer", nameUrdu = "عشاء کی نماز", startTime = "21:00", endTime = "21:45", isFixedPrayer = true, dateString = today)
                    )
                    prayers.forEach { repository.insert(it) }
                }
            }
            // Reschedule alarms for compliance
            scheduleDailyAlarms(context)
        }
    }

    // Manual serialization / deserialization helpers for task data backups
    private fun serializeTask(task: Task): String {
        return listOf(
            task.nameEnglish,
            task.nameUrdu,
            task.startTime,
            task.endTime,
            if (task.isCompleted) "1" else "0",
            if (task.isFailed) "1" else "0",
            if (task.isFixedPrayer) "1" else "0",
            if (task.isPermanent) "1" else "0",
            task.dateString,
            task.targetWeekdays,
            if (task.isLocked) "1" else "0",
            if (task.isImportant) "1" else "0"
        ).joinToString("|")
    }

    private fun deserializeTask(str: String): Task? {
        val parts = str.split("|")
        if (parts.size < 12) return null
        return Task(
            nameEnglish = parts[0],
            nameUrdu = parts[1],
            startTime = parts[2],
            endTime = parts[3],
            isCompleted = parts[4] == "1",
            isFailed = parts[5] == "1",
            isFixedPrayer = parts[6] == "1",
            isPermanent = parts[7] == "1",
            dateString = parts[8],
            targetWeekdays = parts[9],
            isLocked = parts[10] == "1",
            isImportant = parts[11] == "1"
        )
    }

    private fun serializeTaskList(list: List<Task>): String {
        return list.joinToString("\n") { serializeTask(it) }
    }

    private fun deserializeTaskList(str: String): List<Task> {
        if (str.isBlank()) return emptyList()
        return str.split("\n").mapNotNull { deserializeTask(it) }
    }

    val hasSavedDataForCurrentMode = MutableStateFlow(false)

    fun updateHasSavedDataFlag() {
        val currentModeKey = if (isMuslimMode.value) "muslim" else "non_muslim"
        hasSavedDataForCurrentMode.value = prefs.getBoolean("has_saved_data_$currentModeKey", false)
    }

    fun switchReligiousMode(context: Context, keepData: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldModeKey = if (isMuslimMode.value) "muslim" else "non_muslim"
            val newMode = !isMuslimMode.value

            if (keepData) {
                val currentTasks = repository.getAllTasks()
                val serialized = serializeTaskList(currentTasks)
                prefs.edit()
                    .putString("saved_tasks_$oldModeKey", serialized)
                    .putBoolean("has_saved_data_$oldModeKey", true)
                    .apply()
            } else {
                prefs.edit()
                    .remove("saved_tasks_$oldModeKey")
                    .putBoolean("has_saved_data_$oldModeKey", false)
                    .apply()
            }

            // Clear current active db entries
            repository.clear()
            repository.deleteFixedPrayers()

            // Apply new mode preference
            prefs.edit().putBoolean("is_muslim_mode", newMode).apply()
            isMuslimMode.value = newMode
            updateHasSavedDataFlag()

            // Force dynamic reinitialization
            val today = getTodayDateString()
            initializedDates.clear()
            initializeDayIfEmpty(today)

            // Reschedule compliance alarms
            scheduleDailyAlarms(context)
        }
    }

    fun loadSavedDataForCurrentMode(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentModeKey = if (isMuslimMode.value) "muslim" else "non_muslim"
            val serialized = prefs.getString("saved_tasks_$currentModeKey", "") ?: ""
            if (serialized.isNotBlank()) {
                val tasksToLoad = deserializeTaskList(serialized)
                
                // Clear existing db items
                repository.clear()
                repository.deleteFixedPrayers()

                tasksToLoad.forEach {
                    val freshCopy = it.copy(id = 0)
                    repository.insert(freshCopy)
                }

                // Delete backup flag
                prefs.edit()
                    .remove("saved_tasks_$currentModeKey")
                    .putBoolean("has_saved_data_$currentModeKey", false)
                    .apply()
                updateHasSavedDataFlag()

                // Reschedule compliance alarms
                scheduleDailyAlarms(context)
            }
        }
    }

    fun setLanguage(lang: String) {
        prefs.edit().putString("current_language", lang).apply()
        currentLanguage.value = lang
        
        // Urdu loyalty compatibility check
        isUrduLoyal.value = (lang == "Urdu")
        prefs.edit().putBoolean("is_urdu_loyal", lang == "Urdu").apply()
    }

    fun setAlarmPermanentUri(uri: String) {
        prefs.edit().putString("alarm_permanent_uri", uri).apply()
        alarmPermanentUri.value = uri
    }

    fun setAlarmImportantUri(uri: String) {
        prefs.edit().putString("alarm_important_uri", uri).apply()
        alarmImportantUri.value = uri
    }

    fun setAlarmNormalUri(uri: String) {
        prefs.edit().putString("alarm_normal_uri", uri).apply()
        alarmNormalUri.value = uri
    }

    fun setNotificationPermanentUri(uri: String) {
        prefs.edit().putString("notification_permanent_uri", uri).apply()
        notificationPermanentUri.value = uri
    }

    fun setNotificationImportantUri(uri: String) {
        prefs.edit().putString("notification_important_uri", uri).apply()
        notificationImportantUri.value = uri
    }

    fun setNotificationNormalUri(uri: String) {
        prefs.edit().putString("notification_normal_uri", uri).apply()
        notificationNormalUri.value = uri
    }

    fun setAppVolume(volume: Float) {
        prefs.edit().putFloat("app_volume_percent", volume).apply()
        appVolume.value = volume
    }

    fun setGlobalSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_global_sound_enabled", enabled).apply()
        isGlobalSoundEnabled.value = enabled
        if (!enabled) {
            stopPreviewSound()
            try {
                val context = getApplication<android.app.Application>()
                val intent = Intent(context, com.example.service.TimetableForegroundService::class.java).apply {
                    action = "ACTION_MUTE"
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("TimetableViewModel", "Failed to send ACTION_MUTE to service", e)
            }
        }
    }

    private var previewMediaPlayer: MediaPlayer? = null

    fun playPreviewSound(context: Context, uriStr: String, key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stopPreviewSound()
                val uri = if (uriStr.isNotBlank()) Uri.parse(uriStr) else {
                    when (key) {
                        "alarm_permanent" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        "alarm_important" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        "alarm_normal" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        "notification_permanent" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        "notification_important" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        "notification_normal" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    }
                }
                
                if (uri == null) return@launch
                
                val mp = MediaPlayer().apply {
                    setDataSource(context, uri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    isLooping = false
                    prepare()
                    
                    val volPercent = prefs.getFloat("app_volume_percent", 100f)
                    val vol = volPercent / 100f
                    setVolume(vol, vol)
                    start()
                }
                previewMediaPlayer = mp
                isPreviewPlaying.value = key
                
                mp.setOnCompletionListener {
                    it.release()
                    if (previewMediaPlayer == mp) {
                        previewMediaPlayer = null
                        isPreviewPlaying.value = null
                    }
                }
            } catch (e: Exception) {
                Log.e("SoundSettings", "Failed to play preview", e)
                isPreviewPlaying.value = null
            }
        }
    }

    fun stopPreviewSound() {
        try {
            previewMediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            previewMediaPlayer = null
            isPreviewPlaying.value = null
        } catch (e: Exception) {
            Log.e("SoundSettings", "Failed to stop preview", e)
        }
    }

    fun triggerTaskAlarm(task: Task, context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            val serviceIntent = Intent(context, com.example.service.TimetableForegroundService::class.java).apply {
                putExtra("taskId", task.id)
                putExtra("taskName", task.nameEnglish)
                putExtra("isStart", true)
                putExtra("isFiveMinBeforeEnd", false)
                putExtra("isPermanent", task.isPermanent)
                putExtra("isImportant", task.isImportant)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Toast.makeText(context, "Alarm / Timer triggered for: ${task.nameEnglish}", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopActiveAlarm(context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            val serviceIntent = Intent(context, com.example.service.TimetableForegroundService::class.java)
            context.stopService(serviceIntent)
            Toast.makeText(context, "Active alerts silenced", Toast.LENGTH_SHORT).show()
        }
    }

    fun addMinutesToTime(timeStr: String, minutesToAdd: Int): String {
        try {
            val parts = timeStr.split(":")
            if (parts.size != 2) return timeStr
            val hrs = parts[0].trim().toInt()
            val mins = parts[1].trim().toInt()
            var totalMins = hrs * 60 + mins + minutesToAdd
            totalMins = totalMins % 1440
            if (totalMins < 0) totalMins += 1440
            val h = totalMins / 60
            val m = totalMins % 60
            return String.format("%02d:%02d", h, m)
        } catch (e: Exception) {
            return timeStr
        }
    }

    fun fetchLocationAndInitializeTimes(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var location: Location? = null
                
                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                ) {
                    val providers = locationManager.getProviders(true)
                    for (provider in providers) {
                        try {
                            val loc = locationManager.getLastKnownLocation(provider)
                            if (loc != null) {
                                if (location == null || loc.accuracy < location.accuracy) {
                                    location = loc
                                }
                            }
                        } catch (e: SecurityException) {
                            // permission check is already done, but guard catch anyway
                        }
                    }
                }
                
                val lat = location?.latitude ?: 24.8607 // fallback Karachi
                val lon = location?.longitude ?: 67.0011 // fallback Karachi
                
                val client = okhttp3.OkHttpClient()
                val url = "https://api.aladhan.com/v1/timings?latitude=$lat&longitude=$lon&method=1"
                val request = okhttp3.Request.Builder().url(url).build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = org.json.JSONObject(body)
                        val dataObj = json.getJSONObject("data")
                        val timingsObj = dataObj.getJSONObject("timings")
                        
                        val fajr = timingsObj.getString("Fajr")
                        val dhuhr = timingsObj.getString("Dhuhr")
                        val asr = timingsObj.getString("Asr")
                        val maghrib = timingsObj.getString("Maghrib")
                        val isha = timingsObj.getString("Isha")
                        val sunrise = timingsObj.getString("Sunrise")
                        val sunset = timingsObj.getString("Sunset")
                        
                        updateTodayTimesWithFetchedValues(
                            fajr = fajr,
                            dhuhr = dhuhr,
                            asr = asr,
                            maghrib = maghrib,
                            isha = isha,
                            sunrise = sunrise,
                            sunset = sunset
                        )
                        
                        viewModelScope.launch(Dispatchers.Main) {
                            Toast.makeText(context, "Synchronized times from location coordinates!", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        viewModelScope.launch(Dispatchers.Main) {
                            Toast.makeText(context, "Location sync failed. Using regional defaults.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationAPI", "Failed to fetch timings", e)
                viewModelScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Could not reach timing server.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateTodayTimesWithFetchedValues(
        fajr: String,
        dhuhr: String,
        asr: String,
        maghrib: String,
        isha: String,
        sunrise: String,
        sunset: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit().apply {
                putString("default_fajr_start", fajr)
                putString("default_dhuhr_start", dhuhr)
                putString("default_asr_start", asr)
                putString("default_maghrib_start", maghrib)
                putString("default_isha_start", isha)
                putString("default_morning_start", sunrise)
                putString("default_night_start", addMinutesToTime(sunset, 120))
                apply()
            }

            val today = getTodayDateString()
            val currentTasks = repository.getTasksForDate(today)
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
                            val duration = timeToMinutes(task.endTime) - timeToMinutes(task.startTime)
                            val finalDuration = if (duration <= 0) 45 else duration
                            val updatedTask = task.copy(
                                startTime = newStart,
                                endTime = addMinutesToTime(newStart, finalDuration)
                            )
                            repository.update(updatedTask)
                        }
                    }
                }
            } else {
                currentTasks.forEach { task ->
                    val newStart = when {
                        task.nameEnglish.contains("Good Morning", ignoreCase = true) -> sunrise
                        task.nameEnglish.contains("Good Night", ignoreCase = true) -> addMinutesToTime(sunset, 120)
                        else -> null
                    }
                    if (newStart != null) {
                        val duration = timeToMinutes(task.endTime) - timeToMinutes(task.startTime)
                        val finalDuration = if (duration <= 0) 60 else duration
                        val updatedTask = task.copy(
                            startTime = newStart,
                            endTime = addMinutesToTime(newStart, finalDuration)
                        )
                        repository.update(updatedTask)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        stopPreviewSound()
        super.onCleared()
    }

    private suspend fun validateLiveFailures() {
        val nowMin = _currentMinutes.value
        val todayStr = getTodayDateString()
        
        // Loop over today's tasks and auto-fail completed tasks if window expired
        val list = repository.getTasksForDate(todayStr)
        list.forEach { task ->
            val endMin = timeToMinutes(task.endTime)
            if (nowMin > endMin && !task.isCompleted && !task.isFailed) {
                repository.update(task.copy(isFailed = true))
            } else if (nowMin <= endMin && task.isFailed) {
                repository.update(task.copy(isFailed = false))
            }
        }
    }

    fun getCompliancePercentage(taskList: List<Task>): Int {
        if (taskList.isEmpty()) return 0
        val completed = taskList.count { it.isCompleted }
        return ((completed.toFloat() / taskList.size) * 100).toInt()
    }

    fun calculateMonthlyPerformanceTitle() {
        viewModelScope.launch(Dispatchers.IO) {
            val allTasks = repository.getAllTasks()
            
            // Count unique logged days
            val uniqueDays = allTasks.map { it.dateString }.distinct()
            totalTrackedDays.value = uniqueDays.size
            
            // Overall status is evaluated and displayed only at the end of the month (every 40 days)
            val isEndOfPeriod = uniqueDays.size >= 40
            showMonthlyReport.value = isEndOfPeriod

            if (allTasks.isEmpty()) {
                monthlyPerformancePercent.value = 0
                monthlyTitle.value = "Serious"
                return@launch
            }

            val cal = Calendar.getInstance()
            val fortyDayRanges = mutableSetOf<String>()
            for (i in 0 until 40) {
                val d = cal.clone() as Calendar
                d.add(Calendar.DAY_OF_YEAR, -i)
                val yr = d.get(Calendar.YEAR)
                val mn = d.get(Calendar.MONTH) + 1
                val dy = d.get(Calendar.DAY_OF_MONTH)
                fortyDayRanges.add(String.format("%04d-%02d-%02d", yr, mn, dy))
            }

            val filterTasks = allTasks.filter { it.dateString in fortyDayRanges }
            if (filterTasks.isEmpty()) {
                monthlyPerformancePercent.value = 0
                monthlyTitle.value = "Serious"
                return@launch
            }

            val completed = filterTasks.count { it.isCompleted }
            val total = filterTasks.size
            val ratio = (completed * 100) / total

            monthlyPerformancePercent.value = ratio
            monthlyTitle.value = when {
                ratio < 50 -> "Lazy"
                ratio <= 85 -> "Serious"
                else -> "Elite"
            }
        }
    }

    private fun scheduleAlarmsForTask(context: Context, task: Task) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val currentStr = getTodayDateString()
        if (task.dateString != currentStr) return

        val startMin = timeToMinutes(task.startTime)
        val endMin = timeToMinutes(task.endTime)

        val taskDisplayName = if (task.nameEnglish.isNotBlank()) task.nameEnglish else task.nameUrdu

        val isPermanentTask = task.isPermanent || task.isFixedPrayer
        val isImportantTask = task.isImportant

        // 1. Start alarm setup
        val startIntent = Intent(context, TimetableAlarmReceiver::class.java).apply {
            putExtra("taskId", task.id)
            putExtra("taskName", taskDisplayName)
            putExtra("isStart", true)
            putExtra("isFiveMinBeforeEnd", false)
            putExtra("isPermanent", isPermanentTask)
            putExtra("isImportant", isImportantTask)
        }
        val startPending = PendingIntent.getBroadcast(
            context,
            task.id * 10,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. End alarm setup
        val endIntent = Intent(context, TimetableAlarmReceiver::class.java).apply {
            putExtra("taskId", task.id)
            putExtra("taskName", taskDisplayName)
            putExtra("isStart", false)
            putExtra("isFiveMinBeforeEnd", false)
            putExtra("isPermanent", isPermanentTask)
            putExtra("isImportant", isImportantTask)
        }
        val endPending = PendingIntent.getBroadcast(
            context,
            task.id * 10 + 1,
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Status Check alarm 5 mins before end
        val checkMin = maxOf(startMin, endMin - 5)
        val checkIntent = Intent(context, TimetableAlarmReceiver::class.java).apply {
            putExtra("taskId", task.id)
            putExtra("taskName", taskDisplayName)
            putExtra("isStart", false)
            putExtra("isFiveMinBeforeEnd", true)
            putExtra("isPermanent", isPermanentTask)
            putExtra("isImportant", isImportantTask)
        }
        val checkPending = PendingIntent.getBroadcast(
            context,
            task.id * 10 + 2,
            checkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Calculate Calendar times
        val calStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startMin / 60)
            set(Calendar.MINUTE, startMin % 60)
            set(Calendar.SECOND, 0)
        }
        if (calStart.timeInMillis > System.currentTimeMillis()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calStart.timeInMillis, startPending)
            } catch (sec: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, calStart.timeInMillis, startPending)
            }
        }

        val calEnd = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, endMin / 60)
            set(Calendar.MINUTE, endMin % 60)
            set(Calendar.SECOND, 0)
        }
        if (calEnd.timeInMillis > System.currentTimeMillis()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calEnd.timeInMillis, endPending)
            } catch (sec: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, calEnd.timeInMillis, endPending)
            }
        }

        // Only schedule if 5 mins before end occurs in the future
        val calCheck = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, checkMin / 60)
            set(Calendar.MINUTE, checkMin % 60)
            set(Calendar.SECOND, 0)
        }
        if (calCheck.timeInMillis > System.currentTimeMillis()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calCheck.timeInMillis, checkPending)
            } catch (sec: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, calCheck.timeInMillis, checkPending)
            }
        }
    }

    fun scheduleDailyAlarms(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getTasksForDate(getTodayDateString())
            list.forEach { task ->
                scheduleAlarmsForTask(context, task)
            }
        }
    }

    fun timeToMinutes(timeStr: String): Int {
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
            if (isPm && hour < 12) {
                hour += 12
            } else if (isAm && hour == 12) {
                hour = 0
            }
        }
        
        return (hour * 60 + min) % 1440
    }
}

class TimetableViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TimetableViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TimetableViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
