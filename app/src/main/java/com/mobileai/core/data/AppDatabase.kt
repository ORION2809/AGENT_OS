package com.mobileai.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import java.io.File

/**
 * The real exported symbol, confirmed via `llvm-nm -D libvec0.so` against the actual prebuilt
 * binary -- NOT "sqlite3_vec0_init" as the "sqlite3_<basename>_init" auto-detection convention
 * would suggest. The library is named vec0.so but registers itself internally as "vec", so the
 * entry point is sqlite3_vec_init. Passed explicitly to avoid relying on auto-detection.
 */
private const val VEC0_ENTRY_POINT = "sqlite3_vec_init"

@Database(
    entities = [ContactEntity::class, EventEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun eventDao(): EventDao

    companion object {
        // Android's stock SQLite has extension loading compiled out; RequerySQLiteOpenHelperFactory
        // swaps in requery/sqlite-android's build, which supports it. See docs/query_decomposer_design.md
        // and the SQLite-backend decision recorded for this project.
        fun build(context: Context, dbName: String = "memory.db"): AppDatabase {
            val vec0Path = File(context.applicationInfo.nativeLibraryDir, "libvec0.so").absolutePath

            val factory = RequerySQLiteOpenHelperFactory(
                listOf(RequerySQLiteOpenHelperFactory.ConfigurationOptions { config ->
                    config.customExtensions.add(SQLiteCustomExtension(vec0Path, VEC0_ENTRY_POINT))
                    config
                })
            )

            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, dbName)
                .openHelperFactory(factory)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        createEmbeddingVirtualTable(db)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        createEmbeddingVirtualTable(db)
                    }
                })
                .build()
        }

        /**
         * event_embeddings is a vec0 virtual table, not a Room @Entity -- Room's annotation
         * processor has no concept of third-party virtual table modules, so it's managed here
         * with raw SQL instead. Dimension (4) matches the placeholder test fixture in
         * research/sqlite-vec-verify; this becomes EmbeddingGemma's real output dimension
         * once that pass is wired in.
         */
        private fun createEmbeddingVirtualTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS event_embeddings USING vec0(
                    event_id INTEGER PRIMARY KEY,
                    embedding FLOAT[4]
                )
                """.trimIndent()
            )
        }
    }
}
