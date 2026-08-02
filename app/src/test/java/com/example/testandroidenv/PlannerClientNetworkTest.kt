package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.contract.PlannerRequest
import com.example.testandroidenv.agent.latency.PlannerLatencyContext
import com.example.testandroidenv.agent.latency.PlannerTransportTiming
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class PlannerClientNetworkTest {
    @Test
    fun sendsExactV02PayloadAndParsesSuccessfulResponse() = runBlocking {
        var capturedBody = ""
        val server = server { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            respond(exchange, 200, successfulResponse())
        }
        try {
            val decision = PlannerClient(endpoint(server)).plan(request())
            assertEquals("open_app", decision.action.wireValue)
            val json = JSONObject(capturedBody)
            assertEquals(
                setOf(
                    "user_input",
                    "active_goal",
                    "current_app",
                    "ui_state",
                    "conversation_context",
                    "last_action_result",
                    "action_history"
                ),
                json.keys().asSequence().toSet()
            )
            assertEquals("افتح واتساب", json.getString("user_input"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun http422PreservesUsefulValidationDetail() {
        val server = server { exchange ->
            respond(
                exchange,
                422,
                """{"detail":[{"loc":["body","active_goal"],"msg":"field required"}]}"""
            )
        }
        try {
            val error = assertThrows(PlannerException::class.java) {
                runBlocking { PlannerClient(endpoint(server)).plan(request()) }
            }
            assertEquals(422, error.httpStatus)
            assertTrue(error.message.orEmpty().contains("active_goal"))
            assertTrue(error.responseBody.orEmpty().contains("field required"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun timeoutFailsWithoutProducingAPlannerDecision() {
        val server = server { exchange ->
            Thread.sleep(250)
            runCatching { respond(exchange, 200, successfulResponse()) }
        }
        try {
            val error = assertThrows(PlannerException::class.java) {
                runBlocking {
                    PlannerClient(
                        endpoint = endpoint(server),
                        connectTimeoutMs = 100,
                        readTimeoutMs = 40
                    ).plan(request())
                }
            }
            assertTrue(error.message.orEmpty().contains("no action was retried"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun reportsClientBreakdownAndUsesOnlyExplicitServerTiming() = runBlocking {
        var timing: PlannerTransportTiming? = null
        val server = server { exchange ->
            respond(
                exchange,
                200,
                successfulResponse(),
                headers = mapOf("X-Planner-Duration-Ms" to "23")
            )
        }
        try {
            withContext(PlannerLatencyContext("goal", 1) { timing = it }) {
                PlannerClient(endpoint(server)).plan(request())
            }
            val measured = requireNotNull(timing)
            assertEquals(23L, measured.serverDurationMs)
            assertTrue(measured.serializationMs >= 0L)
            assertTrue(measured.networkRoundTripMs >= 0L)
            assertTrue(measured.responseParsingMs >= 0L)
        } finally {
            server.stop(0)
        }
    }

    private fun request() = PlannerRequest(
        userInput = "افتح واتساب",
        activeGoal = "افتح واتساب",
        currentApp = CurrentApp(
            packageName = "dynamic.launcher",
            displayName = "Home",
            appId = "android_launcher",
            screenName = "Home Screen"
        ),
        uiState = CompactUiState(1, emptyList()),
        conversationContext = emptyList(),
        lastActionResult = null,
        actionHistory = emptyList()
    )

    private fun server(handler: (HttpExchange) -> Unit): HttpServer {
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/predict", handler)
            start()
        }
    }

    private fun endpoint(server: HttpServer): String {
        return "http://127.0.0.1:${server.address.port}/api/v1/predict"
    }

    private fun successfulResponse(): String = """
        {
          "reason":"واتساب غير مفتوح.",
          "status":"continue",
          "action":"open_app",
          "target":"WhatsApp",
          "target_id":null,
          "value":null,
          "message":null
        }
    """.trimIndent()

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        headers.forEach(exchange.responseHeaders::add)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
