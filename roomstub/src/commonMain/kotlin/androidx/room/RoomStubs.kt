package androidx.room

import kotlin.reflect.KClass

annotation class Entity(val tableName: String = "")
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class Dao
annotation class Query(val value: String)
annotation class Transaction

class OnConflictStrategy {
    companion object {
        const val REPLACE = 1
    }
}

annotation class Insert(val onConflict: Int = 0)

annotation class Database(
    val entities: Array<KClass<*>>,
    val version: Int,
    val exportSchema: Boolean = true,
)

interface RoomDatabaseConstructor<T : RoomDatabase> {
    fun initialize(): T
}

annotation class ConstructedBy(val value: KClass<out RoomDatabaseConstructor<*>>)

abstract class RoomDatabase {
    open fun close() {}
}
