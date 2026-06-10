package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM timetable_tasks ORDER BY dateString DESC, startTime ASC")
    fun getAllTasksFlow(): Flow<List<Task>>

    @Query("SELECT * FROM timetable_tasks WHERE dateString = :dateStr ORDER BY startTime ASC")
    fun getTasksForDateFlow(dateStr: String): Flow<List<Task>>

    @Query("SELECT * FROM timetable_tasks WHERE dateString = :dateStr ORDER BY startTime ASC")
    suspend fun getTasksForDate(dateStr: String): List<Task>

    @Query("SELECT * FROM timetable_tasks ORDER BY dateString DESC, startTime ASC")
    suspend fun getAllTasks(): List<Task>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Delete
    suspend fun deleteTask(task: Task)

    @Query("DELETE FROM timetable_tasks WHERE id = :id AND isFixedPrayer = 0")
    suspend fun deleteTaskById(id: Int)

    @Query("DELETE FROM timetable_tasks WHERE isFixedPrayer = 1")
    suspend fun deleteFixedPrayers()

    @Query("SELECT * FROM timetable_tasks WHERE id = :id")
    suspend fun getTaskById(id: Int): Task?

    @Query("DELETE FROM timetable_tasks WHERE isFixedPrayer = 0")
    suspend fun deleteAllCustomTasks()
}
