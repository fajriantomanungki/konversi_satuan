package id.go.konversisatuan.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionDao {
    @Query("SELECT * FROM conversions WHERE approved = 1 ORDER BY commodity COLLATE NOCASE")
    fun approved(): Flow<List<Conversion>>
    @Query("SELECT * FROM conversions ORDER BY approved ASC, commodity COLLATE NOCASE")
    fun all(): Flow<List<Conversion>>
    @Query("SELECT * FROM conversions WHERE approved = 0 ORDER BY updatedAt DESC")
    fun pending(): Flow<List<Conversion>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(items: List<Conversion>)
    @Query("DELETE FROM conversions WHERE id = :id")
    suspend fun delete(id: String)
    @Query("SELECT * FROM conversions")
    suspend fun snapshot(): List<Conversion>
}

@Database(entities = [Conversion::class], version = 1, exportSchema = false)
abstract class LocalDb : RoomDatabase() {
    abstract fun conversions(): ConversionDao
    companion object {
        @Volatile private var INSTANCE: LocalDb? = null
        fun get(context: Context): LocalDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, LocalDb::class.java, "konversi-satuan.db").build().also { INSTANCE = it }
        }
    }
}
