package com.mobileai.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobileai.core.data.AppDatabase
import com.mobileai.core.data.ContactEntity
import com.mobileai.core.data.EmbeddingStore
import com.mobileai.core.data.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device counterpart to research/sqlite-vec-verify/test_combined_query.py: proves the
 * requery/sqlite-android + sqlite-vec combined structured+similarity query actually works
 * against Android's real SQLite stack (not just desktop Python), per the SQLite-backend
 * decision recorded for this project. Replace with the real app UI once this is confirmed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var status by remember { mutableStateOf("Running on-device sqlite-vec verification...") }

                    LaunchedEffect(Unit) {
                        status = withContext(Dispatchers.IO) {
                            runVerification(this@MainActivity)
                        }
                    }

                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = status)
                    }
                }
            }
        }
    }
}

private suspend fun runVerification(activity: MainActivity): String {
    return try {
        val db = AppDatabase.build(activity, dbName = "verify-${System.nanoTime()}.db")
        val now = 1_000_000_000_000L // fixed epoch for reproducibility

        val rahulId = db.contactDao().insert(
            ContactEntity(name = "Rahul", lastInteractionEpochMs = now, interactionCount90d = 12)
        )
        val priyaId = db.contactDao().insert(
            ContactEntity(name = "Priya", lastInteractionEpochMs = now, interactionCount90d = 3)
        )

        val proposalReview = db.eventDao().insert(
            EventEntity(title = "Phoenix architecture proposal review", participantContactId = rahulId, eventTimeEpochMs = now + 86_400_000)
        )
        val weeklySync = db.eventDao().insert(
            EventEntity(title = "Weekly sync", participantContactId = rahulId, eventTimeEpochMs = now - 172_800_000)
        )
        val budgetPlanning = db.eventDao().insert(
            EventEntity(title = "Budget planning", participantContactId = priyaId, eventTimeEpochMs = now)
        )
        val documentDiscussion = db.eventDao().insert(
            EventEntity(title = "Document discussion re: Phoenix rollout", participantContactId = rahulId, eventTimeEpochMs = now - 86_400_000)
        )

        val embeddingStore = EmbeddingStore(db)
        embeddingStore.insertEmbedding(proposalReview, floatArrayOf(0.10f, 0.90f, 0.05f, 0.02f))
        embeddingStore.insertEmbedding(weeklySync, floatArrayOf(0.90f, 0.10f, 0.05f, 0.02f))
        embeddingStore.insertEmbedding(budgetPlanning, floatArrayOf(0.05f, 0.05f, 0.90f, 0.10f))
        embeddingStore.insertEmbedding(documentDiscussion, floatArrayOf(0.02f, 0.03f, 0.05f, 0.95f))

        val queryVec = floatArrayOf(0.01f, 0.02f, 0.04f, 0.97f) // "document discussion"

        val results = embeddingStore.queryByParticipantAndSimilarity(
            queryEmbedding = queryVec,
            participantContactId = rahulId,
            fromEpochMs = now - (14L * 86_400_000),
            toEpochMs = now + (14L * 86_400_000),
            k = 5,
        )

        val summary = results.joinToString("\n") { "  event ${it.eventId} '${it.title}' distance=${it.distance}" }

        if (results.isNotEmpty() && results.first().eventId == documentDiscussion) {
            "PASS: combined structured+vector query works on-device.\nTop result correctly ranked first:\n$summary"
        } else {
            "FAIL: expected event $documentDiscussion ranked first.\nGot:\n$summary"
        }
    } catch (e: Exception) {
        "FAIL: ${e::class.simpleName}: ${e.message}\n${e.stackTraceToString().take(1500)}"
    }
}
