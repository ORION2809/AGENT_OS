package com.mobileai.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Escapes SQL LIKE metacharacters ('%', '_') and the escape character itself in a fragment
 * that will be wrapped in '%...%' for a fuzzy match. Without this, a person_reference.raw_text
 * containing a literal '%' or '_' (LLM/STT output, not sanitized user text) silently produces
 * unintended wildcard matching instead of a narrow fuzzy match -- caught in review before this
 * shipped with real LLM-extracted slot values flowing through it.
 */
fun escapeLikeFragment(fragment: String): String =
    fragment.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

@Dao
interface ContactDao {
    @Insert
    suspend fun insert(contact: ContactEntity): Long

    /** Trigram/substring fuzzy match -- entity linking's SQL leg (docs/query_decomposer_design.md section 3). */
    @Query("SELECT * FROM contacts WHERE name LIKE '%' || :escapedNameFragment || '%' ESCAPE '\\'")
    suspend fun fuzzyMatchByNameEscaped(escapedNameFragment: String): List<ContactEntity>

    suspend fun fuzzyMatchByName(nameFragment: String): List<ContactEntity> =
        fuzzyMatchByNameEscaped(escapeLikeFragment(nameFragment))
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query(
        "SELECT * FROM events WHERE participant_contact_id = :contactId " +
            "AND event_time_epoch_ms BETWEEN :fromEpochMs AND :toEpochMs " +
            "ORDER BY event_time_epoch_ms"
    )
    suspend fun byParticipantAndTimeWindow(
        contactId: Long,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<EventEntity>

    /**
     * Time-window-only lookup, no participant filter -- docs/query_decomposer_design.md section 2
     * row 2 treats person_reference as optional for an event_reference-only retrieval ("filtered
     * by fuzzy time window + resolved participant *if present*"). Missing this method was a real
     * gap: QueryDecomposer had no way to look up "that meeting tomorrow" with no person mentioned
     * at all, and silently fell back to NotFound instead.
     */
    @Query(
        "SELECT * FROM events WHERE event_time_epoch_ms BETWEEN :fromEpochMs AND :toEpochMs " +
            "ORDER BY event_time_epoch_ms"
    )
    suspend fun byTimeWindow(fromEpochMs: Long, toEpochMs: Long): List<EventEntity>
}
