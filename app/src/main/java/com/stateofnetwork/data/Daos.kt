package com.stateofnetwork.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stateofnetwork.data.model.DomainCheckItemEntity
import com.stateofnetwork.data.model.DomainCheckRunEntity
import com.stateofnetwork.data.model.SpeedTestResultEntity
import kotlinx.coroutines.flow.Flow

/**
 * Агрегатная статистика скорости (сводка для экрана История).
 */
data class SpeedAgg(
    val networkType: String,
    val avgDown: Double?,
    val avgUp: Double?,
    val avgPing: Double?,
    val avgRuPing: Double?,
    val samples: Int
)

@Dao
interface SpeedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SpeedTestResultEntity)

    @Query("SELECT * FROM speed_results ORDER BY timestamp DESC LIMIT 1")
    fun observeLast(): Flow<SpeedTestResultEntity?>

    @Query("SELECT downloadMbps FROM speed_results ORDER BY timestamp DESC LIMIT :limit")
    fun observeLastNDownload(limit: Int): Flow<List<Double>>

    @Query("SELECT uploadMbps FROM speed_results ORDER BY timestamp DESC LIMIT :limit")
    fun observeLastNUpload(limit: Int): Flow<List<Double>>

    @Query(
        """
SELECT networkType AS networkType,
       AVG(downloadMbps) AS avgDown,
       AVG(uploadMbps) AS avgUp,
       AVG(latencyMs) AS avgPing,
       AVG(ruLatencyMs) AS avgRuPing,
       COUNT(*) AS samples
FROM speed_results
WHERE timestamp >= :from
GROUP BY networkType
"""
    )
    fun observeAggSince(from: Long): Flow<List<SpeedAgg>>

    @Query("SELECT * FROM speed_results WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getBetween(from: Long, to: Long): List<SpeedTestResultEntity>

    // История использует timestamp как идентификатор записи. На случай коллизий по времени
    // берём самую новую строку по id.
    @Query("SELECT * FROM speed_results WHERE timestamp = :ts ORDER BY id DESC LIMIT 1")
    suspend fun getByTimestamp(ts: Long): SpeedTestResultEntity?
}

@Dao
interface DomainDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: DomainCheckRunEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<DomainCheckItemEntity>)

    // При равных timestamp порядок строк в SQLite не гарантирован.
    // Добавляем id DESC, чтобы "последняя" проверка выбиралась детерминированно.
    @Query("SELECT * FROM domain_runs ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun observeLastRun(): Flow<DomainCheckRunEntity?>

    @Query("SELECT * FROM domain_runs WHERE id = :id LIMIT 1")
    suspend fun getRunById(id: Long): DomainCheckRunEntity?

    // Обновляем статус без REPLACE: REPLACE в SQLite это delete+insert, что может приводить к
    // неожиданным эффектам с автоинкрементным ключом и наблюдаемыми Flow.
    @Query("UPDATE domain_runs SET summaryStatus = :status, notes = :notes WHERE id = :id")
    suspend fun updateRunStatus(id: Long, status: String, notes: String?)

    @Query("SELECT * FROM domain_runs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getRunsBetween(from: Long, to: Long): List<DomainCheckRunEntity>

    @Query("SELECT * FROM domain_items WHERE runId IN (:runIds) ORDER BY runId ASC, domain ASC")
    suspend fun getItemsForRuns(runIds: List<Long>): List<DomainCheckItemEntity>
}
