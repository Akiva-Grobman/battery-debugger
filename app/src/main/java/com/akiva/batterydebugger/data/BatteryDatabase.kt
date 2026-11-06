package com.akiva.batterydebugger.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Insert
    suspend fun insert(sample: Sample)

    @Insert
    suspend fun insert(marker: Marker)

    @Query("SELECT * FROM samples WHERE timestampMs >= :sinceMs ORDER BY timestampMs")
    fun samplesSince(sinceMs: Long): Flow<List<Sample>>

    @Query("SELECT * FROM markers WHERE timestampMs >= :sinceMs ORDER BY timestampMs")
    fun markersSince(sinceMs: Long): Flow<List<Marker>>

    @Query("SELECT * FROM samples ORDER BY timestampMs DESC LIMIT :count")
    suspend fun latestSamples(count: Int): List<Sample>

    @Query("SELECT * FROM samples ORDER BY timestampMs")
    suspend fun allSamples(): List<Sample>

    @Query("SELECT * FROM markers ORDER BY timestampMs")
    suspend fun allMarkers(): List<Marker>

    @Query("DELETE FROM samples WHERE timestampMs < :beforeMs")
    suspend fun deleteSamplesBefore(beforeMs: Long)

    @Query("DELETE FROM markers WHERE timestampMs < :beforeMs")
    suspend fun deleteMarkersBefore(beforeMs: Long)
}

@Database(entities = [Sample::class, Marker::class], version = 1, exportSchema = false)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun records(): RecordDao

    companion object {
        @Volatile private var instance: BatteryDatabase? = null

        fun get(context: Context): BatteryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "battery.db",
                ).build().also { instance = it }
            }
    }
}
