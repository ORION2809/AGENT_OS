package com.mobileai.core.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanValidatorTest {

    @Test
    fun `valid flight alarm plan parses and passes validation`() {
        // The exact model output verified in research/eval-harness/cases.json flight_alarm_basic.
        val rawJson = """
            {"schema_version":"1","goal":"CREATE_ALARM","confidence":0.85,
             "slots":{"event_reference":{"raw_text":"my 8:30 flight tomorrow"},
             "temporal_expression":{"raw_text":"an hour before I have to leave"},
             "numeric_constraints":[
               {"kind":"offset_before","value":60,"unit":"minutes"},
               {"kind":"duration","value":45,"unit":"minutes"},
               {"kind":"buffer","value":2,"unit":"hours"}
             ]},"retrieved_context_used":[],"clarification_needed":null}
        """.trimIndent()

        val result = PlanValidator.parseAndValidate(rawJson)

        assertTrue(result is PlanParseResult.Valid)
        val plan = (result as PlanParseResult.Valid).plan
        assertEquals(Goal.CREATE_ALARM, plan.goal)
        assertEquals(3, plan.slots.numericConstraints.size)
    }

    @Test
    fun `confidence out of range is rejected`() {
        val rawJson = """
            {"schema_version":"1","goal":"QUERY_INFO","confidence":1.5,
             "slots":{},"retrieved_context_used":[],"clarification_needed":null}
        """.trimIndent()

        val result = PlanValidator.parseAndValidate(rawJson)

        assertTrue(result is PlanParseResult.Invalid)
        assertTrue((result as PlanParseResult.Invalid).reasons.any { it.contains("confidence") })
    }

    @Test
    fun `unsupported schema_version is rejected`() {
        val rawJson = """
            {"schema_version":"2","goal":"QUERY_INFO","confidence":0.9,
             "slots":{},"retrieved_context_used":[],"clarification_needed":null}
        """.trimIndent()

        val result = PlanValidator.parseAndValidate(rawJson)

        assertTrue(result is PlanParseResult.Invalid)
        assertTrue((result as PlanParseResult.Invalid).reasons.any { it.contains("schema_version") })
    }

    @Test
    fun `action goal with no populated slots is rejected as likely hallucination`() {
        val rawJson = """
            {"schema_version":"1","goal":"CREATE_ALARM","confidence":0.9,
             "slots":{},"retrieved_context_used":[],"clarification_needed":null}
        """.trimIndent()

        val result = PlanValidator.parseAndValidate(rawJson)

        assertTrue(result is PlanParseResult.Invalid)
        assertTrue((result as PlanParseResult.Invalid).reasons.any { it.contains("no populated slots") })
    }

    @Test
    fun `QUERY_INFO with entirely empty slots is still valid -- not every query needs a slot to act on`() {
        val rawJson = """
            {"schema_version":"1","goal":"QUERY_INFO","confidence":0.95,
             "slots":{},"retrieved_context_used":[],"clarification_needed":null}
        """.trimIndent()

        val result = PlanValidator.parseAndValidate(rawJson)

        assertTrue(result is PlanParseResult.Valid)
    }

    @Test
    fun `malformed JSON is rejected with a clear reason`() {
        val result = PlanValidator.parseAndValidate("{not valid json")

        assertTrue(result is PlanParseResult.Invalid)
    }
}
