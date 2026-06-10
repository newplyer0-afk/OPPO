package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timetable_tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nameEnglish: String,
    val nameUrdu: String,
    val startTime: String,     // format: "HH:mm"
    val endTime: String,       // format: "HH:mm"
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val isFixedPrayer: Boolean = false,
    val isPermanent: Boolean = false,
    val dateString: String,     // format: "YYYY-MM-DD" style to preserve historic logs
    val targetWeekdays: String = "1111111", // represents Mon-Sun, '1' active, '0' inactive
    val isLocked: Boolean = true,            // Lock state default true
    val isImportant: Boolean = false        // Smart important flag
)
