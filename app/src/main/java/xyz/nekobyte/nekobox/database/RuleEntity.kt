package xyz.nekobyte.nekobox.database

import android.os.Parcelable
import androidx.room.*
import kotlinx.parcelize.Parcelize

@Entity(tableName = "rules")
@Parcelize
@TypeConverters(StringCollectionConverter::class)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var name: String = "",
    @ColumnInfo(defaultValue = "")
    var config: String = "",
    var userOrder: Long = 0L,
    var enabled: Boolean = false,
    var domains: String = "",
    var ip: String = "",
    var port: String = "",
    var sourcePort: String = "",
    var network: String = "",
    var source: String = "",
    var protocol: String = "",
    @ColumnInfo(name = "ruleset", defaultValue = "")
    var ruleset: String = "",
    var outbound: Long = 0,
    var packages: Set<String> = emptySet(),
    @ColumnInfo(defaultValue = "0") var dnsThroughOutbound: Boolean = false,
    @ColumnInfo(defaultValue = "") var dnsServer: String = "",
) : Parcelable {

    fun displayName(): String = name.takeIf { it.isNotBlank() } ?: "Rule $id"

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM rules ORDER BY userOrder")
        fun allRules(): List<RuleEntity>

        @Query("SELECT * FROM rules WHERE enabled = :enabled ORDER BY userOrder")
        fun enabledRules(enabled: Boolean = true): List<RuleEntity>

        @Query("UPDATE rules SET userOrder = :order WHERE id = :id")
        fun updateOrder(id: Long, order: Long): Int

        @Query("SELECT MAX(userOrder) + 1 FROM rules")
        fun nextOrder(): Long?

        @Query("SELECT * FROM rules WHERE id = :ruleId")
        fun getById(ruleId: Long): RuleEntity?

        @Query("DELETE FROM rules WHERE id = :ruleId")
        fun deleteById(ruleId: Long): Int

        @Delete
        fun deleteRule(rule: RuleEntity)

        @Delete
        fun deleteRules(rules: List<RuleEntity>)

        @Insert
        fun createRule(rule: RuleEntity): Long

        @Update
        fun updateRule(rule: RuleEntity)

        @Update
        fun updateRules(rules: List<RuleEntity>)

        @Query("DELETE FROM rules")
        fun reset()

        @Insert
        fun insert(rules: List<RuleEntity>)
    }
}
