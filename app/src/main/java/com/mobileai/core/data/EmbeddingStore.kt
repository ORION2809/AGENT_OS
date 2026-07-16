package com.mobileai.core.data

import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EventSimilarityResult(
    val eventId: Long,
    val title: String,
    val eventTimeEpochMs: Long,
    val distance: Double,
)

fun FloatArray.toVecBlob(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buffer.putFloat(it) }
    return buffer.array()
}

/**
 * Raw-SQL access to the event_embeddings vec0 virtual table (see AppDatabase.createEmbeddingVirtualTable).
 * Implements the joined structured+vector query pattern from docs/query_decomposer_design.md section 2,
 * row 3 -- the exact query verified against desktop sqlite-vec in research/sqlite-vec-verify.
 *
 * Takes the [RoomDatabase] itself rather than a cached [SupportSQLiteDatabase] -- caching the raw
 * handle risked going stale across Room lifecycle events (a prior version of this class did that;
 * caught in review). Every call fetches `openHelper.writableDatabase` fresh and runs on
 * Dispatchers.IO, matching the same off-caller-thread contract Room's own generated DAOs already
 * give their suspend functions -- also caught in review, since QueryDecomposer was calling these
 * methods directly inside suspend functions with no dispatcher confinement of its own.
 */
class EmbeddingStore(private val roomDb: RoomDatabase) {

    private val db: SupportSQLiteDatabase
        get() = roomDb.openHelper.writableDatabase

    suspend fun insertEmbedding(eventId: Long, embedding: FloatArray) = withContext(Dispatchers.IO) {
        db.execSQL(
            "INSERT INTO event_embeddings (event_id, embedding) VALUES (?, ?)",
            arrayOf(eventId, embedding.toVecBlob()),
        )
    }

    /**
     * Combined structured (participant + time window) + vector similarity query in one
     * statement, per the confirmed capability in docs/verification_findings.md.
     */
    suspend fun queryByParticipantAndSimilarity(
        queryEmbedding: FloatArray,
        participantContactId: Long,
        fromEpochMs: Long,
        toEpochMs: Long,
        k: Int = 5,
    ): List<EventSimilarityResult> = withContext(Dispatchers.IO) {
        val query = SimpleSQLiteQuery(
            """
            SELECT e.event_id, e.title, e.event_time_epoch_ms, ee.distance
            FROM event_embeddings ee
            JOIN events e ON e.event_id = ee.event_id
            WHERE ee.embedding MATCH ?
              AND e.participant_contact_id = ?
              AND e.event_time_epoch_ms BETWEEN ? AND ?
              AND k = ?
            ORDER BY ee.distance
            """.trimIndent(),
            arrayOf(queryEmbedding.toVecBlob(), participantContactId, fromEpochMs, toEpochMs, k),
        )
        readResults(query)
    }

    /**
     * Row 4 of docs/query_decomposer_design.md section 2: topic_reference with no structured
     * anchor (no person/event to filter by) -- pure vector search, no WHERE clause beyond MATCH.
     * Kept as a distinct method rather than a sentinel/nullable participant id on the query
     * above, since "no filter" and "filter that matches nothing" must never look the same query.
     */
    suspend fun queryBySimilarityOnly(queryEmbedding: FloatArray, k: Int = 5): List<EventSimilarityResult> =
        withContext(Dispatchers.IO) {
            val query = SimpleSQLiteQuery(
                """
                SELECT e.event_id, e.title, e.event_time_epoch_ms, ee.distance
                FROM event_embeddings ee
                JOIN events e ON e.event_id = ee.event_id
                WHERE ee.embedding MATCH ?
                  AND k = ?
                ORDER BY ee.distance
                """.trimIndent(),
                arrayOf(queryEmbedding.toVecBlob(), k),
            )
            readResults(query)
        }

    private fun readResults(query: SimpleSQLiteQuery): List<EventSimilarityResult> {
        db.query(query).use { cursor ->
            val results = mutableListOf<EventSimilarityResult>()
            while (cursor.moveToNext()) {
                results.add(
                    EventSimilarityResult(
                        eventId = cursor.getLong(0),
                        title = cursor.getString(1),
                        eventTimeEpochMs = cursor.getLong(2),
                        distance = cursor.getDouble(3),
                    )
                )
            }
            return results
        }
    }
}
