package com.eignex.lab

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

class InputSigningRelayTest {
    @Test fun `a preparation consumer receives only a scoped input capability`() {
        val sha = "a".repeat(64)
        var asked: InputSignature? = null
        InputSigningRelay { signature ->
            asked = signature
            SignedInput("https://store.example/input-blobs/$sha", mapOf("If-None-Match" to "*"))
        }.use { relay ->
            val body = Json.encodeToString(InputSignature.serializer(), InputSignature("PUT", "input-blobs/$sha", sha))
            val request = HttpRequest.newBuilder(URI(relay.address)).POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertEquals(InputSignature("PUT", "input-blobs/$sha", sha), asked)
            assertEquals("https://store.example/input-blobs/$sha", Json.decodeFromString<SignedInput>(response.body()).url)
            assertFalse(response.body().contains("Authorization"))
        }
    }
}
