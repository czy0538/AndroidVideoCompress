package com.videocompress.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompressionTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<CompressionTask>)

    @Query("SELECT * FROM compression_tasks WHERE batchId = :batchId ORDER BY id ASC")
    fun getTasksByBatch(batchId: String): Flow<List<CompressionTask>>

    @Query("SELECT * FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS') ORDER BY id ASC LIMIT 1")
    suspend fun getNextPendingTask(): CompressionTask?

    @Query("SELECT COUNT(*) FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS')")
    suspend fun getUnfinishedCount(): Int

    @Query("SELECT * FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS') ORDER BY id ASC")
    suspend fun getUnfinishedTasks(): List<CompressionTask>

    @Query("UPDATE compression_tasks SET status = :status, errorMessage = :error, compressedSize = :compressedSize WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: Long, status: TaskStatus, error: String? = null, compressedSize: Long? = null)

    @Query("UPDATE compression_tasks SET status = 'PENDING' WHERE status = 'IN_PROGRESS'")
    suspend fun resetInProgressTasks()

    @Query("SELECT * FROM compression_tasks WHERE batchId = (SELECT batchId FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS') LIMIT 1) ORDER BY id ASC")
    fun getActiveTasksBatch(): Flow<List<CompressionTask>>

    @Query("SELECT batchId FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS') LIMIT 1")
    suspend fun getActiveBatchId(): String?

    @Query("DELETE FROM compression_tasks WHERE batchId = :batchId")
    suspend fun deleteTasksByBatch(batchId: String)
}
