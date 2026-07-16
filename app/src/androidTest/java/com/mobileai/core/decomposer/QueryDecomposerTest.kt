package com.mobileai.core.decomposer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobileai.core.data.AppDatabase
import com.mobileai.core.data.ContactEntity
import com.mobileai.core.data.EmbeddingStore
import com.mobileai.core.data.EventEntity
import com.mobileai.core.plan.EventReference
import com.mobileai.core.plan.PersonReference
import com.mobileai.core.plan.Slots
import com.mobileai.core.plan.TopicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented (real Android SQLite stack, not a JVM fake) test of the exact worked example in
 * docs/query_decomposer_design.md section 6: "Remind me before that meeting with Rahul to bring
 * the document we discussed yesterday." Runs on-device rather than mocked, since the whole point
 * of this component is the requery/sqlite-vec joined query already confirmed in
 * docs/verification_findings.md -- a JVM fake DB would not exercise that path at all.
 */
@RunWith(AndroidJUnit4::class)
class QueryDecomposerTest {

    private val now = 1_000_000_000_000L
    private val openDbs = mutableListOf<AppDatabase>()

    private fun buildDb(): AppDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return AppDatabase.build(context, dbName = "decomposer-test-${System.nanoTime()}.db")
            .also { openDbs.add(it) }
    }

    @After
    fun closeDatabases() {
        openDbs.forEach { it.close() }
        openDbs.clear()
    }

    @Test
    fun worked_example_resolves_event_and_topic_together() = runBlocking {
        val db = buildDb()
        val rahulId = db.contactDao().insert(
            ContactEntity(name = "Rahul", lastInteractionEpochMs = now, interactionCount90d = 12)
        )
        val meetingId = db.eventDao().insert(
            EventEntity(title = "that meeting with Rahul", participantContactId = rahulId, eventTimeEpochMs = now - 86_400_000)
        )
        val unrelatedId = db.eventDao().insert(
            EventEntity(title = "Weekly sync", participantContactId = rahulId, eventTimeEpochMs = now + 86_400_000)
        )

        val embeddingStore = EmbeddingStore(db)
        embeddingStore.insertEmbedding(meetingId, floatArrayOf(0.02f, 0.03f, 0.05f, 0.95f))
        embeddingStore.insertEmbedding(unrelatedId, floatArrayOf(0.90f, 0.10f, 0.05f, 0.02f))

        // Fake embedder standing in for EmbeddingGemma (not wired in yet -- see QueryDecomposer.kt):
        // "the document we discussed yesterday" should embed close to the meeting's own vector.
        val embedder = Embedder { text ->
            if (text.contains("document")) floatArrayOf(0.01f, 0.02f, 0.04f, 0.97f)
            else floatArrayOf(0f, 0f, 0f, 0f)
        }

        val decomposer = QueryDecomposer(
            contactDao = db.contactDao(),
            eventDao = db.eventDao(),
            embeddingStore = embeddingStore,
            embedder = embedder,
            nowEpochMs = { now },
        )

        val slots = Slots(
            personReference = PersonReference(rawText = "Rahul"),
            eventReference = EventReference(rawText = "that meeting", fuzzyTimeHint = null),
            topicReference = TopicReference(rawText = "the document we discussed yesterday"),
        )

        val result = decomposer.resolveEventAndTopic(slots)

        assertTrue(result is RetrievedContext.EventMatch)
        assertEquals(meetingId, (result as RetrievedContext.EventMatch).event.eventId)
    }

    @Test
    fun ambiguous_person_reference_surfaces_for_clarification_not_guessed() = runBlocking {
        val db = buildDb()
        // Two "Rahul"s, tied on both recency and frequency -- must not silently pick one.
        db.contactDao().insert(ContactEntity(name = "Rahul Sharma", lastInteractionEpochMs = now, interactionCount90d = 5))
        db.contactDao().insert(ContactEntity(name = "Rahul Verma", lastInteractionEpochMs = now, interactionCount90d = 5))

        val decomposer = QueryDecomposer(
            contactDao = db.contactDao(),
            eventDao = db.eventDao(),
            embeddingStore = EmbeddingStore(db),
            embedder = Embedder { floatArrayOf(0f, 0f, 0f, 0f) },
            nowEpochMs = { now },
        )

        val result = decomposer.resolvePerson("Rahul")

        assertTrue(result is EntityLinkResult.Ambiguous)
        assertEquals(2, (result as EntityLinkResult.Ambiguous).candidates.size)
    }

    @Test
    fun disambiguates_by_recency_when_not_tied() = runBlocking {
        val db = buildDb()
        db.contactDao().insert(ContactEntity(name = "Rahul Old", lastInteractionEpochMs = now - 86_400_000, interactionCount90d = 20))
        val recentId = db.contactDao().insert(ContactEntity(name = "Rahul Recent", lastInteractionEpochMs = now, interactionCount90d = 1))

        val decomposer = QueryDecomposer(
            contactDao = db.contactDao(),
            eventDao = db.eventDao(),
            embeddingStore = EmbeddingStore(db),
            embedder = Embedder { floatArrayOf(0f, 0f, 0f, 0f) },
            nowEpochMs = { now },
        )

        val result = decomposer.resolvePerson("Rahul")

        assertTrue(result is EntityLinkResult.Resolved)
        assertEquals(recentId, (result as EntityLinkResult.Resolved).contactId)
    }

    @Test
    fun unresolvable_person_short_circuits_to_not_found_without_running_doomed_query() = runBlocking {
        val db = buildDb()
        val decomposer = QueryDecomposer(
            contactDao = db.contactDao(),
            eventDao = db.eventDao(),
            embeddingStore = EmbeddingStore(db),
            embedder = Embedder { floatArrayOf(0f, 0f, 0f, 0f) },
            nowEpochMs = { now },
        )

        val slots = Slots(
            personReference = PersonReference(rawText = "NobodyKnownByThisName"),
            eventReference = EventReference(rawText = "that meeting", fuzzyTimeHint = null),
        )

        val result = decomposer.resolveEventAndTopic(slots)

        assertEquals(RetrievedContext.NotFound, result)
    }

    @Test
    fun ambiguous_person_in_event_lookup_surfaces_clarification_not_not_found() = runBlocking {
        val db = buildDb()
        // Two "Rahul"s tied on recency and frequency -- resolveEventAndTopic must not collapse
        // this into NotFound (a real bug caught in review: the two outcomes mean different
        // things to the Reasoning LLM pass -- "ask which Rahul" vs. "no such event exists").
        db.contactDao().insert(ContactEntity(name = "Rahul Sharma", lastInteractionEpochMs = now, interactionCount90d = 5))
        db.contactDao().insert(ContactEntity(name = "Rahul Verma", lastInteractionEpochMs = now, interactionCount90d = 5))

        val decomposer = QueryDecomposer(
            contactDao = db.contactDao(),
            eventDao = db.eventDao(),
            embeddingStore = EmbeddingStore(db),
            embedder = Embedder { floatArrayOf(0f, 0f, 0f, 0f) },
            nowEpochMs = { now },
        )

        val slots = Slots(
            personReference = PersonReference(rawText = "Rahul"),
            eventReference = EventReference(rawText = "that meeting", fuzzyTimeHint = null),
        )

        val result = decomposer.resolveEventAndTopic(slots)

        assertTrue(result is RetrievedContext.NeedsClarification)
        assertEquals(2, (result as RetrievedContext.NeedsClarification).candidates.size)
    }

    @Test
    fun event_reference_without_person_reference_resolves_via_time_window_only() = runBlocking {
        val db = buildDb()
        // No person mentioned at all -- e.g. "remind me about that meeting tomorrow." Must
        // resolve via time window alone, not short-circuit to NotFound just because no
        // person_reference slot was populated (a real bug caught in review: EventDao had no
        // time-window-only lookup, so this utterance shape silently always failed).
        val meetingId = db.eventDao().insert(
            EventEntity(title = "Standalone meeting", participantContactId = null, eventTimeEpochMs = now)
        )

        val decomposer = QueryDecomposer(
            contactDao = db.contactDao(),
            eventDao = db.eventDao(),
            embeddingStore = EmbeddingStore(db),
            embedder = Embedder { floatArrayOf(0f, 0f, 0f, 0f) },
            nowEpochMs = { now },
        )

        val slots = Slots(
            eventReference = EventReference(rawText = "that meeting", fuzzyTimeHint = null),
        )

        val result = decomposer.resolveEventAndTopic(slots)

        assertTrue(result is RetrievedContext.EventMatch)
        assertEquals(meetingId, (result as RetrievedContext.EventMatch).event.eventId)
    }
}
