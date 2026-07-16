package com.mobileai.core.decomposer

import com.mobileai.core.data.ContactDao
import com.mobileai.core.data.ContactEntity
import com.mobileai.core.data.EmbeddingStore
import com.mobileai.core.data.EventDao
import com.mobileai.core.data.EventEntity
import com.mobileai.core.plan.Slots

/**
 * Implements docs/query_decomposer_design.md end to end: the slot-type -> retrieval-strategy
 * table (section 2), entity linking (section 3), the relevance filter (section 4), and the
 * empty-retrieval relaxation ladder (section 5). Built on the joined structured+vector query
 * confirmed in docs/verification_findings.md sections 2-3.
 *
 * Embedding generation is injected rather than hard-coded: EmbeddingGemma isn't wired in yet
 * (Vision.md section 6 names it, but that inference pass hasn't been built), so callers supply
 * an [Embedder]. This keeps the decomposer's retrieval logic testable and correct now, without
 * blocking on that separate piece of work.
 */
fun interface Embedder {
    suspend fun embed(text: String): FloatArray
}

/** A vector hit is dropped if it exceeds this distance -- see section 4's "no fixed top-K" rule. */
private const val DEFAULT_VECTOR_DISTANCE_CEILING = 0.5

private const val NO_TIME_HINT_WINDOW_DAYS = 14L
private const val RELAXED_TIME_WINDOW_DAYS = 1L
private const val DAY_MS = 86_400_000L

sealed interface RetrievedContext {
    data class EventMatch(val event: EventEntity, val distance: Double? = null) : RetrievedContext

    /**
     * Section 3's explicit rule: multiple candidates tied even after deterministic tie-break
     * must be surfaced for clarification, never silently guessed or collapsed into "not found" --
     * those are semantically different outcomes for the Reasoning LLM pass (a plan built on
     * NotFound should try again/report absence; one built on NeedsClarification should ask which
     * person was meant).
     */
    data class NeedsClarification(val reason: String, val candidates: List<ContactEntity> = emptyList()) : RetrievedContext

    data object NotFound : RetrievedContext
}

/**
 * Entity linking result per docs/query_decomposer_design.md section 3. Ambiguous means multiple
 * candidates tied even after the deterministic tie-break order -- surfaced for clarification
 * rather than guessed, per that section's explicit rule.
 */
sealed interface EntityLinkResult {
    data class Resolved(val contactId: Long) : EntityLinkResult
    data object NotFound : EntityLinkResult
    data class Ambiguous(val candidates: List<ContactEntity>) : EntityLinkResult
}

class QueryDecomposer(
    private val contactDao: ContactDao,
    private val eventDao: EventDao,
    private val embeddingStore: EmbeddingStore,
    private val embedder: Embedder,
    private val nowEpochMs: () -> Long,
    private val vectorDistanceCeiling: Double = DEFAULT_VECTOR_DISTANCE_CEILING,
) {

    /** Section 3: resolve "Rahul" -> a specific contact_id, or flag ambiguity/absence. */
    suspend fun resolvePerson(rawText: String): EntityLinkResult {
        val candidates = contactDao.fuzzyMatchByName(rawText)
        return when {
            candidates.isEmpty() -> EntityLinkResult.NotFound
            candidates.size == 1 -> EntityLinkResult.Resolved(candidates[0].contactId)
            else -> disambiguate(candidates)
        }
    }

    /** Deterministic tie-break: most recent interaction, then highest 90-day frequency. */
    private fun disambiguate(candidates: List<ContactEntity>): EntityLinkResult {
        val byRecency = candidates.sortedByDescending { it.lastInteractionEpochMs }
        val topRecency = byRecency.first().lastInteractionEpochMs
        val tiedOnRecency = byRecency.filter { it.lastInteractionEpochMs == topRecency }

        if (tiedOnRecency.size == 1) {
            return EntityLinkResult.Resolved(tiedOnRecency.first().contactId)
        }

        val byFrequency = tiedOnRecency.sortedByDescending { it.interactionCount90d }
        val topFrequency = byFrequency.first().interactionCount90d
        val tiedOnFrequency = byFrequency.filter { it.interactionCount90d == topFrequency }

        return if (tiedOnFrequency.size == 1) {
            EntityLinkResult.Resolved(tiedOnFrequency.first().contactId)
        } else {
            EntityLinkResult.Ambiguous(tiedOnFrequency)
        }
    }

    /**
     * Row 2/3 of section 2's table: event_reference alone is a structured SQL lookup;
     * event_reference + topic_reference together becomes the single joined query.
     * person_reference is optional here (section 2 row 2: "filtered by fuzzy time window
     * + resolved participant *if present*") -- a person-less "that meeting tomorrow" must
     * still resolve via time window alone, not short-circuit to NotFound.
     */
    suspend fun resolveEventAndTopic(slots: Slots): RetrievedContext {
        val eventRef = slots.eventReference ?: return RetrievedContext.NotFound
        val topicRef = slots.topicReference

        val now = nowEpochMs()
        val (fromMs, toMs) = timeWindowFor(eventRef.fuzzyTimeHint, now)

        val personRef = slots.personReference
        if (personRef == null) {
            return resolveByTimeWindowOnly(topicRef?.rawText, fromMs, toMs)
        }

        return when (val personLink = resolvePerson(personRef.rawText)) {
            is EntityLinkResult.Ambiguous ->
                RetrievedContext.NeedsClarification(
                    reason = "multiple contacts match '${personRef.rawText}'",
                    candidates = personLink.candidates,
                )
            // A specific person was named but couldn't be resolved at all -- section 5/6 treat
            // this as a dead end for this query rather than falling back to an unfiltered search
            // (the utterance was about a specific person's event, not "any event").
            EntityLinkResult.NotFound -> RetrievedContext.NotFound
            is EntityLinkResult.Resolved -> resolveByParticipant(personLink.contactId, topicRef?.rawText, fromMs, toMs)
        }
    }

    private suspend fun resolveByParticipant(
        contactId: Long,
        topicRawText: String?,
        fromMs: Long,
        toMs: Long,
    ): RetrievedContext {
        if (topicRawText != null) {
            val queryVec = embedder.embed(topicRawText)
            val hits = embeddingStore.queryByParticipantAndSimilarity(
                queryEmbedding = queryVec,
                participantContactId = contactId,
                fromEpochMs = fromMs,
                toEpochMs = toMs,
            )
            val relevant = hits.filter { it.distance <= vectorDistanceCeiling }
            if (relevant.isNotEmpty()) {
                val top = relevant.first()
                return RetrievedContext.EventMatch(
                    EventEntity(top.eventId, top.title, contactId, top.eventTimeEpochMs),
                    distance = top.distance,
                )
            }
            // Section 4: vector leg empty under threshold but SQL leg may still match --
            // fall through to the plain structured lookup rather than reporting nothing.
        }
        return sqlOnlyLookupByParticipant(contactId, fromMs, toMs)
    }

    private suspend fun resolveByTimeWindowOnly(
        topicRawText: String?,
        fromMs: Long,
        toMs: Long,
    ): RetrievedContext {
        if (topicRawText != null) {
            val queryVec = embedder.embed(topicRawText)
            val hits = embeddingStore.queryBySimilarityOnly(queryVec)
                .filter { it.eventTimeEpochMs in fromMs..toMs }
                .filter { it.distance <= vectorDistanceCeiling }
            if (hits.isNotEmpty()) {
                val top = hits.first()
                return RetrievedContext.EventMatch(
                    EventEntity(top.eventId, top.title, null, top.eventTimeEpochMs),
                    distance = top.distance,
                )
            }
        }
        val rows = eventDao.byTimeWindow(fromMs, toMs)
        return rows.firstOrNull()?.let { RetrievedContext.EventMatch(it) } ?: RetrievedContext.NotFound
    }

    private suspend fun sqlOnlyLookupByParticipant(contactId: Long, fromMs: Long, toMs: Long): RetrievedContext {
        val rows = eventDao.byParticipantAndTimeWindow(contactId, fromMs, toMs)
        if (rows.isNotEmpty()) return RetrievedContext.EventMatch(rows.first())

        // Section 5 relaxation ladder step 2: widen the window before giving up.
        val relaxedFrom = fromMs - RELAXED_TIME_WINDOW_DAYS * DAY_MS
        val relaxedTo = toMs + RELAXED_TIME_WINDOW_DAYS * DAY_MS
        val relaxedRows = eventDao.byParticipantAndTimeWindow(contactId, relaxedFrom, relaxedTo)
        return relaxedRows.firstOrNull()?.let { RetrievedContext.EventMatch(it) } ?: RetrievedContext.NotFound
    }

    /** No fuzzy_time_hint -> default to a trailing/leading 14-day window (section 6 worked example). */
    private fun timeWindowFor(fuzzyTimeHint: String?, now: Long): Pair<Long, Long> {
        if (fuzzyTimeHint == null) {
            return (now - NO_TIME_HINT_WINDOW_DAYS * DAY_MS) to (now + NO_TIME_HINT_WINDOW_DAYS * DAY_MS)
        }
        // Real fuzzy-time parsing belongs to the deterministic Temporal Reasoning Engine
        // (Vision.md section 2), not this component -- placeholder window until that's wired in.
        return (now - NO_TIME_HINT_WINDOW_DAYS * DAY_MS) to (now + NO_TIME_HINT_WINDOW_DAYS * DAY_MS)
    }

    /** Row 4 of section 2's table: topic_reference alone, no structured anchor to filter by. */
    suspend fun resolveTopicOnly(rawText: String): List<com.mobileai.core.data.EventSimilarityResult> {
        val queryVec = embedder.embed(rawText)
        return embeddingStore.queryBySimilarityOnly(queryVec)
            .filter { it.distance <= vectorDistanceCeiling }
    }
}
