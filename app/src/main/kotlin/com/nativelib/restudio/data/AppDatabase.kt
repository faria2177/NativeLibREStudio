package com.nativelib.restudio.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One completed analysis of an imported file. Full results are regenerated from the stored input copy. */
@Entity(tableName = "analysis", indices = [Index("createdAt"), Index("sha256")])
data class AnalysisRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val containerType: String,
    val sizeBytes: Long,
    val sha256: String,
    val abis: String,
    val libraryCount: Int,
    val verifiedCount: Int,
    val issueCount: Int,
    val errorCount: Int,
    val createdAt: Long,
    /** Absolute path of this analysis' private workspace directory. */
    val workspaceDir: String,
    /** File name of the imported copy inside workspaceDir/input. */
    val inputFileName: String,
)

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM analysis ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AnalysisRecord>>

    @Query("SELECT * FROM analysis WHERE id = :id")
    suspend fun get(id: Long): AnalysisRecord?

    @Insert
    suspend fun insert(record: AnalysisRecord): Long

    @Query("DELETE FROM analysis WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM analysis")
    suspend fun deleteAll()
}

@Database(entities = [AnalysisRecord::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun analysisDao(): AnalysisDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nlre.db").build()
    }
}
