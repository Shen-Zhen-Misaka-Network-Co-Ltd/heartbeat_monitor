package com.heartwith.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

private val ApiJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class HeartwithApi(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 2_500
            requestTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
        install(ContentNegotiation) {
            json(ApiJson)
        }
    },
) {
    suspend fun createSession(request: SessionRequest): SessionResponse =
        client.post("$baseUrl/api/v1/collector/sessions") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun uploadBatch(token: String, payload: BatchPayload): IngestResponse {
        val bytes = payload.toSerdeCompatibleCbor()
        val response = client.post("$baseUrl/api/v1/hr/batches") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(ByteArrayContent(bytes, ContentType("application", "cbor")))
        }
        response.throwIfFailed("upload cbor heart-rate batch")
        return response.body()
    }

    suspend fun lobby(): LobbyResponse =
        client.get("$baseUrl/api/v1/lobby/participants").body()

    suspend fun participantSeries(
        collectorId: String,
        windowSeconds: Long = 300,
        maxPoints: Long = if (windowSeconds >= 6 * 60 * 60) 420 else 600,
    ): SeriesResponse =
        client.get("$baseUrl/api/v1/participants/$collectorId/series?window_seconds=$windowSeconds&max_points=$maxPoints").body()

    suspend fun rawLobbyEvents(): String =
        client.get("$baseUrl/api/v1/lobby/events").bodyAsText()

    private suspend fun HttpResponse.throwIfFailed(operation: String) {
        if (status.isSuccess()) return
        val body = runCatching { bodyAsText() }.getOrDefault("")
        error("$operation failed: HTTP ${status.value} ${status.description}${body.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}")
    }
}

private fun BatchPayload.toSerdeCompatibleCbor(): ByteArray {
    val out = ArrayList<Byte>(128 + samples.size * 24)
    out.addMapHeader(8)
    out.addText("schema")
    out.addCborInt(schema.toLong())
    out.addText("collector_id")
    out.addText(collectorId)
    out.addText("seq")
    out.addCborInt(seq)
    out.addText("sent_at_ms")
    out.addCborInt(sentAtMs)
    out.addText("display_name")
    out.addText(displayName)
    out.addText("device_model")
    out.addText(deviceModel)
    out.addText("samples")
    out.addArrayHeader(samples.size)
    samples.forEach {
        out.addMapHeader(2)
        out.addText("dt_ms")
        out.addCborInt(it.dtMs)
        out.addText("bpm")
        out.addCborInt(it.bpm.toLong())
    }
    out.addText("ble")
    out.addMapHeader(if (ble.rssi == null) 1 else 2)
    out.addText("source")
    out.addText(ble.source)
    ble.rssi?.let {
        out.addText("rssi")
        out.addCborInt(it.toLong())
    }
    return out.toByteArray()
}

private fun MutableList<Byte>.addCborInt(value: Long) {
    if (value >= 0) {
        addTypeAndValue(0, value)
    } else {
        addTypeAndValue(1, -1L - value)
    }
}

private fun MutableList<Byte>.addText(value: String) {
    val bytes = value.encodeToByteArray()
    addTypeAndValue(3, bytes.size.toLong())
    bytes.forEach { add(it) }
}

private fun MutableList<Byte>.addArrayHeader(size: Int) {
    addTypeAndValue(4, size.toLong())
}

private fun MutableList<Byte>.addMapHeader(size: Int) {
    addTypeAndValue(5, size.toLong())
}

private fun MutableList<Byte>.addTypeAndValue(major: Int, value: Long) {
    val prefix = major shl 5
    when {
        value < 24 -> addByte(prefix or value.toInt())
        value <= 0xff -> {
            addByte(prefix or 24)
            addByte(value.toInt())
        }
        value <= 0xffff -> {
            addByte(prefix or 25)
            addByte((value ushr 8).toInt())
            addByte(value.toInt())
        }
        value <= 0xffff_ffffL -> {
            addByte(prefix or 26)
            for (shift in 24 downTo 0 step 8) addByte((value ushr shift).toInt())
        }
        else -> {
            addByte(prefix or 27)
            for (shift in 56 downTo 0 step 8) addByte((value ushr shift).toInt())
        }
    }
}

private fun MutableList<Byte>.addByte(value: Int) {
    add((value and 0xff).toByte())
}
