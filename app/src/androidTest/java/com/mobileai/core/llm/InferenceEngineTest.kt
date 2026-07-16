package com.mobileai.core.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobileai.core.plan.Goal
import com.mobileai.core.plan.PlanParseResult
import com.mobileai.core.plan.PlanValidator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.time.Duration.Companion.seconds

/**
 * On-device verification that the llama.cpp JNI bridge actually works end to end: load the real
 * Qwen2.5-1.5B-Instruct GGUF, apply the grammar bundled from structured_action_plan.schema.json,
 * and confirm the output is a schema-valid StructuredActionPlan -- the mobile counterpart to the
 * desktop CLI verification in docs/verification_findings.md section 1.
 *
 * Requires the model pushed to the device first (not bundled in the APK due to size):
 *   adb push models/qwen2.5-1.5b-instruct-q4_k_m.gguf /data/local/tmp/
 */
@RunWith(AndroidJUnit4::class)
class InferenceEngineTest {

    private val modelPath = "/data/local/tmp/qwen2.5-1.5b-instruct-q4_k_m.gguf"

    private fun readAsset(name: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.assets.open(name).use { stream ->
            return BufferedReader(InputStreamReader(stream)).readText()
        }
    }

    @Test
    fun flight_alarm_utterance_produces_schema_valid_plan_on_device() = runTest(timeout = 180.seconds) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = InferenceEngineImpl.getInstance(context)

        val grammar = readAsset("structured_action_plan.v1.gbnf")
        val systemPrompt = readAsset("semantic_parser_system_prompt.v1.txt")

        engine.loadModel(modelPath)
        engine.setGrammar(grammar)
        engine.setSystemPrompt(systemPrompt)

        val utterance = "Wake me an hour before I have to leave for my 8:30 flight tomorrow, " +
            "accounting for 45 minutes of travel time and needing to arrive 2 hours early."

        val tokens = engine.sendUserPrompt(utterance, predictLength = 400).toList()
        val rawOutput = tokens.joinToString("")

        val result = PlanValidator.parseAndValidate(rawOutput)
        assertTrue("Expected a valid plan but got: $result\n\nRaw output:\n$rawOutput", result is PlanParseResult.Valid)

        val plan = (result as PlanParseResult.Valid).plan
        assertEquals(Goal.CREATE_ALARM, plan.goal)
        assertEquals(3, plan.slots.numericConstraints.size)

        engine.cleanUp()
    }
}
