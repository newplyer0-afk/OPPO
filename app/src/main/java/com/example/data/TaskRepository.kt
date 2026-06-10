package com.example.data

import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {
    
    fun getTasksForDateFlow(dateStr: String): Flow<List<Task>> {
        return taskDao.getTasksForDateFlow(dateStr)
    }

    fun getAllTasksFlow(): Flow<List<Task>> {
        return taskDao.getAllTasksFlow()
    }

    suspend fun getTasksForDate(dateStr: String): List<Task> {
        return taskDao.getTasksForDate(dateStr)
    }

    suspend fun getAllTasks(): List<Task> {
        return taskDao.getAllTasks()
    }

    suspend fun insert(task: Task) {
        taskDao.insertTask(task)
    }

    suspend fun update(task: Task) {
        taskDao.updateTask(task)
    }

    suspend fun delete(task: Task) {
        taskDao.deleteTask(task)
    }

    suspend fun deleteById(id: Int) {
        taskDao.deleteTaskById(id)
    }

    suspend fun getTaskById(id: Int): Task? {
        return taskDao.getTaskById(id)
    }

    suspend fun deleteFixedPrayers() {
        taskDao.deleteFixedPrayers()
    }

    suspend fun clear() {
        taskDao.deleteAllCustomTasks()
    }
}
