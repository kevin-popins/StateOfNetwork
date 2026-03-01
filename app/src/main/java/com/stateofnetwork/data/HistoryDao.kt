package com.stateofnetwork.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class HistoryItem(
    val sortTs: Long,
    val type: String,
    val displayLine: String
)

@Dao
interface HistoryDao {
    @Query(
        """
        SELECT timestamp AS sortTs, 'speed' AS type,
               ('[СКОРОСТЬ] ' || datetime(timestamp/1000,'unixepoch','localtime') || ' ' ||
                (CASE WHEN networkType='WIFI' THEN 'Wi-Fi' ELSE 'Мобильная' END) ||
                ' скач=' || printf('%.2f', downloadMbps) || ' Мбит/с' ||
                ' отдача=' || printf('%.2f', uploadMbps) || ' Мбит/с' ||
                ' пинг=' || latencyMs || ' мс' ||
                ' пингРФ=' || COALESCE(CAST(ruLatencyMs AS TEXT), '—') || ' мс' ||
                (CASE WHEN ruLatencyTarget IS NOT NULL THEN ' (' || ruLatencyTarget || ')' ELSE '' END) ||
                ' джиттер=' || COALESCE(CAST(jitterMs AS TEXT), '—') || ' мс' ||
                ' сервер=' || endpointId) AS displayLine
        FROM speed_results
        UNION ALL
        SELECT timestamp AS sortTs, 'domains' AS type,
               ('[СЕРВИСЫ] ' || datetime(timestamp/1000,'unixepoch','localtime') || ' ' ||
                (CASE WHEN networkType='WIFI' THEN 'Wi-Fi' ELSE 'Мобильная' END) ||
                ' статус=' ||
                (CASE
                    WHEN summaryStatus='available' THEN 'доступно'
                    WHEN summaryStatus='partial' THEN 'частично'
                    WHEN summaryStatus='unavailable' THEN 'недоступно'
                    ELSE summaryStatus
                 END) ||
                ' наборы=' || selectedServiceSets) AS displayLine
        FROM domain_runs
        ORDER BY sortTs DESC
        LIMIT :limit
        """
    )
    fun observeCombinedLatest(limit: Int): Flow<List<HistoryItem>>
}
