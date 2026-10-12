package com.hydrosense.edge.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HydrationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: HydrationRecordEntity): Long

    @Query("SELECT * FROM hydration_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<HydrationRecordEntity>>

    @Query("SELECT * FROM hydration_records ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentRecords(limit: Int): Flow<List<HydrationRecordEntity>>

    @Query("SELECT * FROM hydration_records WHERE hydrationStatus = :status ORDER BY timestamp DESC")
    fun getRecordsByStatus(status: String): Flow<List<HydrationRecordEntity>>

    @Query("SELECT COUNT(*) FROM hydration_records")
    fun getRecordCount(): Flow<Int>

    @Delete
    suspend fun deleteRecord(record: HydrationRecordEntity)

    @Query("DELETE FROM hydration_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM hydration_records")
    suspend fun clearAll()
}
