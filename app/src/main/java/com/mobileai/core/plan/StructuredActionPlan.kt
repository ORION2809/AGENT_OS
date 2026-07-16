package com.mobileai.core.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors docs/structured_action_plan.schema.json v1 -- the grammar-constrained output
 * contract for the Reasoning LLM's second inference pass (Vision.md sections 4/5).
 *
 * Deserializing successfully already proves the shape is valid (enum membership, required
 * fields, additionalProperties -- all guaranteed by kotlinx.serialization + the GBNF grammar
 * that produced this JSON in the first place). What it does NOT prove is numeric *ranges*:
 * GBNF constrains syntax, not value semantics, so `confidence` being in [0,1] still needs a
 * runtime check -- see PlanValidator.kt.
 */
@Serializable
enum class Goal {
    CREATE_ALARM,
    CREATE_REMINDER,
    CREATE_CALENDAR_EVENT,
    SEND_MESSAGE,
    QUERY_INFO,
    NO_ACTION,
}

@Serializable
data class PersonReference(@SerialName("raw_text") val rawText: String)

@Serializable
data class EventReference(
    @SerialName("raw_text") val rawText: String,
    @SerialName("fuzzy_time_hint") val fuzzyTimeHint: String? = null,
)

@Serializable
data class TopicReference(@SerialName("raw_text") val rawText: String)

@Serializable
data class TemporalExpression(@SerialName("raw_text") val rawText: String)

@Serializable
enum class NumericConstraintKind {
    @SerialName("offset_before") OFFSET_BEFORE,
    @SerialName("offset_after") OFFSET_AFTER,
    @SerialName("duration") DURATION,
    @SerialName("buffer") BUFFER,
}

@Serializable
enum class NumericConstraintUnit {
    @SerialName("minutes") MINUTES,
    @SerialName("hours") HOURS,
    @SerialName("days") DAYS,
}

@Serializable
data class NumericConstraint(
    val kind: NumericConstraintKind,
    val value: Double,
    val unit: NumericConstraintUnit,
)

@Serializable
data class Slots(
    @SerialName("person_reference") val personReference: PersonReference? = null,
    @SerialName("event_reference") val eventReference: EventReference? = null,
    @SerialName("topic_reference") val topicReference: TopicReference? = null,
    @SerialName("temporal_expression") val temporalExpression: TemporalExpression? = null,
    @SerialName("numeric_constraints") val numericConstraints: List<NumericConstraint> = emptyList(),
    @SerialName("message_body") val messageBody: String? = null,
)

@Serializable
enum class RetrievedContextSource {
    @SerialName("sql") SQL,
    @SerialName("vector") VECTOR,
}

@Serializable
data class RetrievedContextRef(
    val source: RetrievedContextSource,
    @SerialName("record_id") val recordId: String,
)

@Serializable
data class StructuredActionPlan(
    @SerialName("schema_version") val schemaVersion: String,
    val goal: Goal,
    val confidence: Double,
    val slots: Slots,
    @SerialName("retrieved_context_used") val retrievedContextUsed: List<RetrievedContextRef> = emptyList(),
    @SerialName("clarification_needed") val clarificationNeeded: String? = null,
)
