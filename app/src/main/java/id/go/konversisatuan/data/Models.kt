package id.go.konversisatuan.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "conversions")
data class Conversion(
    @PrimaryKey val id: String,
    val commodity: String,
    val quality: String,
    val localUnit: String,
    val localValue: Double,
    val standardUnit: String,
    val standardValue: Double,
    val localPrice: Long,
    val kabupaten: String,
    val approved: Boolean = false,
    val contributors: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
) {
}

data class ConversionFile(val schemaVersion: Int = 1, val exportedAt: Long = System.currentTimeMillis(), val conversions: List<Conversion>)

data class UserProfile(val name: String, val role: UserRole, val kabupaten: String)
enum class UserRole(val label: String) { ADMIN("Admin"), SUPERVISOR("Pengawas"), ENUMERATOR("Petugas") }

data class SyncResponse(val conversions: List<Conversion> = emptyList())
