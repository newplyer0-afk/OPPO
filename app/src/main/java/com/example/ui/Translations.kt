package com.example.ui

object Translations {
    val languages = listOf("English")

    val languageDisplayMap = mapOf(
        "English" to "🇺🇸 English"
    )

    private val translations = mapOf(
        "English" to mapOf(
            "app_title" to "TimeFlow Tracker",
            "version_text" to "v2.0.0 Secure Performance Log",
            "lang_select" to "Language",
            "settings" to "Settings",
            "add_task" to "Add Task",
            "reset_all" to "Reset Schedule",
            "schedule_timetable" to "Schedule Timetable",
            "new_task" to "New Task",
            "name_prayer" to "Task / Routine",
            "start" to "Start",
            "end" to "End",
            "status_action" to "Status / Action",
            "failed" to "Failed",
            "completed" to "Completed",
            "locked" to "Locked",
            "mark_complete" to "Mark Complete",
            "cancel" to "Cancel",
            "save_task" to "Save Task",
            "input_lang" to "Input Keyboard Language:",
            "task_name_hint" to "Task Name (e.g. Study Coding)",
            "start_time_hint" to "Start Time (HH:mm format)",
            "end_time_hint" to "End Time (HH:mm format)",
            "repeating_task" to "Daily Repeating Template (Permanent Task)",
            "day_frozen_title" to "Compliance Day Frozen!",
            "day_frozen_desc" to "Report finalized 5 mins after the final task ended. Modifications forbidden.",
            "immutable_record" to "Immutable Record (Locked)",
            "today" to "Today",
            "routine_mode" to "Routine Mode",
            "muslim_mode" to "Muslim Mode (Daily Prayers)",
            "non_muslim_mode" to "Non-Muslim Mode (Custom Baseline)",
            "alarm_sound" to "Active Alarm Tone",
            "notif_sound" to "System Notification Alert",
            "choose_audio" to "Choose Audio File",
            "default_file" to "Default Ringtone",
            "task_details" to "Task Details",
            "unlock_task" to "Unlock Task",
            "delete_task" to "Delete Task",
            "target_weekdays" to "Target Weekdays",
            "mon" to "M", "tue" to "T", "wed" to "W", "thu" to "T", "fri" to "F", "sat" to "S", "sun" to "S"
        )
    )

    fun get(value: String, lang: String = "English"): String {
        val strings = translations["English"]!!
        return strings[value] ?: value
    }
}
