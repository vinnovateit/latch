package androidx.room.migration

import androidx.sqlite.SQLiteConnection

abstract class Migration(
    val startVersion: Int,
    val endVersion: Int,
) {
    abstract fun migrate(connection: SQLiteConnection)
}
