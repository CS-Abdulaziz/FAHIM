package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.Planner
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.PlannerContractJson
import com.example.testandroidenv.agent.contract.PlannerDecision
import com.example.testandroidenv.agent.contract.PlannerRequest
import com.example.testandroidenv.agent.contract.TaskStatus
import com.example.testandroidenv.agent.latency.PlannerLatencyContext
import com.example.testandroidenv.agent.latency.PlannerTransportTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class PlannerException(
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
    val responseBody: String? = null
) : RuntimeException(message, cause)

data class PlannerResponseDto(
    val reason: String?,
    val status: String,
    val action: String?,
    val target: String?,
    val targetId: String?,
    val value: String?,
    val message: String?
)

/** Live Planner API v0.2 client. Task reasoning is never performed on Android. */
class PlannerClient(
    private val endpoint: String = DemoConfig.PLANNER_ENDPOINT,
    private val connectTimeoutMs: Int = DemoConfig.CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DemoConfig.READ_TIMEOUT_MS
) : Planner {
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    override suspend fun plan(request: PlannerRequest): PlannerDecision =
        withContext(Dispatchers.IO) {
            require(request.activeGoal.isNotBlank()) { "active_goal is required." }
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("ngrok-skip-browser-warning", "true")
            }
            activeConnection = connection
            val cancellationHandle = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) connection.disconnect()
            }
            val latencyContext = currentCoroutineContext()[PlannerLatencyContext]
            var serializationMs = 0L
            var networkStartedNanos: Long? = null
            var networkRoundTripMs = 0L
            var serverDurationMs: Long? = null
            var responseParsingMs = 0L

            try {
                val serializationStartedNanos = System.nanoTime()
                val requestBytes = PlannerContractJson.request(request)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                serializationMs = elapsedMillis(serializationStartedNanos)
                connection.setFixedLengthStreamingMode(requestBytes.size)
                networkStartedNanos = System.nanoTime()
                connection.outputStream.use { it.write(requestBytes) }

                val responseCode = connection.responseCode
                serverDurationMs = explicitServerDurationMillis(connection)
                val responseText = readLimitedResponse(
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                )
                networkRoundTripMs = elapsedMillis(requireNotNull(networkStartedNanos))
                if (responseCode !in 200..299) {
                    val diagnostic = responseText
                        .take(DemoConfig.MAX_ERROR_BODY_CHARS)
                        .replace(Regex("\\s+"), " ")
                    throw PlannerException(
                        message = if (responseCode == 422) {
                            "Planner rejected the v0.2 request (HTTP 422): $diagnostic"
                        } else {
                            "Planner returned HTTP $responseCode: $diagnostic"
                        },
                        httpStatus = responseCode,
                        responseBody = diagnostic
                    )
                }
                if (responseText.isBlank()) {
                    throw PlannerException("Planner returned an empty response.")
                }
                val parsingStartedNanos = System.nanoTime()
                try {
                    PlannerResponseJson.parse(responseText)
                } finally {
                    responseParsingMs = elapsedMillis(parsingStartedNanos)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: PlannerException) {
                throw error
            } catch (error: SocketTimeoutException) {
                currentCoroutineContext().ensureActive()
                throw PlannerException("Planner request timed out; no action was retried.", error)
            } catch (error: UnknownHostException) {
                currentCoroutineContext().ensureActive()
                throw PlannerException("Planner host could not be reached.", error)
            } catch (error: ConnectException) {
                currentCoroutineContext().ensureActive()
                throw PlannerException("Could not connect to the Planner.", error)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw PlannerException("Planner network request failed.", error)
            } finally {
                if (networkStartedNanos != null && networkRoundTripMs == 0L) {
                    networkRoundTripMs = elapsedMillis(requireNotNull(networkStartedNanos))
                }
                latencyContext?.report?.invoke(
                    PlannerTransportTiming(
                        serializationMs = serializationMs,
                        networkRoundTripMs = networkRoundTripMs,
                        serverDurationMs = serverDurationMs,
                        responseParsingMs = responseParsingMs
                    )
                )
                cancellationHandle.dispose()
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }

    fun cancelActiveRequest() {
        activeConnection?.disconnect()
        activeConnection = null
    }

    private fun readLimitedResponse(input: InputStream?): String {
        if (input == null) return ""
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4_096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (result.length + count > DemoConfig.MAX_PLANNER_RESPONSE_CHARS) {
                    throw PlannerException("Planner response was too large.")
                }
                result.append(buffer, 0, count)
            }
            return result.toString()
        }
    }

    private fun explicitServerDurationMillis(connection: HttpURLConnection): Long? {
        return SERVER_DURATION_HEADERS.firstNotNullOfOrNull { header ->
            connection.getHeaderField(header)?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long {
        val elapsedNanos = (System.nanoTime() - startedNanos).coerceAtLeast(0L)
        return (elapsedNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
    }

    private companion object {
        val SERVER_DURATION_HEADERS = listOf(
            "X-Planner-Duration-Ms",
            "X-Server-Duration-Ms",
            "Server-Timing-Ms"
        )
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal object PlannerResponseJson {
    private val exactKeys = setOf(
        "reason",
        "status",
        "action",
        "target",
        "target_id",
        "value",
        "message"
    )

    fun parse(responseText: String): PlannerDecision {
        val json = try {
            JSONObject(responseText)
        } catch (error: JSONException) {
            throw PlannerException("Planner returned malformed JSON.", error)
        }
        val keys = json.keys().asSequence().toSet()
        if (keys != exactKeys) {
            throw PlannerException(
                "Planner response keys did not match v0.2. " +
                    "Missing=${exactKeys - keys}, unexpected=${keys - exactKeys}."
            )
        }

        val dto = PlannerResponseDto(
            reason = json.nullableString("reason"),
            status = json.requiredString("status"),
            action = json.nullableString("action"),
            target = json.nullableString("target"),
            targetId = json.nullableString("target_id"),
            value = json.nullableString("value"),
            message = json.nullableString("message")
        )
        val status = TaskStatus.entries.firstOrNull { it.wireValue == dto.status }
            ?: throw PlannerException("Unknown Planner status: ${dto.status}")
        val actionWire = dto.action
            ?: throw PlannerException("Planner response field 'action' is required.")
        val action = PlannerAction.entries.firstOrNull { it.wireValue == actionWire }
            ?: throw PlannerException("Unknown Planner action: $actionWire")
        return PlannerDecision(
            reason = dto.reason,
            status = status,
            action = action,
            target = dto.target,
            targetId = dto.targetId,
            value = dto.value,
            message = dto.message
        )
    }

    private fun JSONObject.requiredString(name: String): String {
        return nullableString(name)?.takeIf(String::isNotBlank)
            ?: throw PlannerException("Planner response field '$name' is required.")
    }

    private fun JSONObject.nullableString(name: String): String? {
        if (isNull(name)) return null
        val value = opt(name)
        if (value !is String) {
            throw PlannerException("Planner response field '$name' must be a string or null.")
        }
        return value
    }
}
