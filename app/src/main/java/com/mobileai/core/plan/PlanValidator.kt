package com.mobileai.core.plan

import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = false }

sealed interface PlanParseResult {
    data class Valid(val plan: StructuredActionPlan) : PlanParseResult
    data class Invalid(val reasons: List<String>) : PlanParseResult
}

/**
 * GBNF grammar-constrained decoding (docs/verification_findings.md section 1) already
 * guarantees valid JSON, closed enum membership, and required fields -- that's proven
 * end-to-end against the real model. What it does NOT guarantee is numeric *range*
 * semantics: GBNF operates on token/character grammar, not value constraints like
 * "confidence must be between 0 and 1". This validator covers exactly that gap, not
 * the shape checks the grammar already handles.
 */
object PlanValidator {

    fun parseAndValidate(rawJson: String): PlanParseResult {
        val plan = try {
            json.decodeFromString(StructuredActionPlan.serializer(), rawJson)
        } catch (e: Exception) {
            return PlanParseResult.Invalid(listOf("JSON did not match StructuredActionPlan shape: ${e.message}"))
        }

        val reasons = mutableListOf<String>()

        if (plan.schemaVersion != "1") {
            reasons.add("unsupported schema_version: ${plan.schemaVersion}")
        }

        if (plan.confidence < 0.0 || plan.confidence > 1.0) {
            reasons.add("confidence out of range [0,1]: ${plan.confidence}")
        }

        plan.slots.numericConstraints.forEachIndexed { index, constraint ->
            if (constraint.value < 0.0) {
                reasons.add("numeric_constraints[$index] has negative value: ${constraint.value}")
            }
        }

        // A plan claiming an action goal with no slots at all to act on is a sign the
        // reasoning pass hallucinated intent rather than extracting it -- surface it as
        // invalid so the Action Validator (Vision.md section 3) never dispatches on it.
        val hasAnySlot = with(plan.slots) {
            personReference != null || eventReference != null || topicReference != null ||
                temporalExpression != null || numericConstraints.isNotEmpty() || messageBody != null
        }
        if (plan.goal != Goal.NO_ACTION && plan.goal != Goal.QUERY_INFO && !hasAnySlot) {
            reasons.add("goal ${plan.goal} has no populated slots to act on")
        }

        return if (reasons.isEmpty()) {
            PlanParseResult.Valid(plan)
        } else {
            PlanParseResult.Invalid(reasons)
        }
    }
}
