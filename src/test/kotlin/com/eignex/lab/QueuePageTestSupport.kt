package com.eignex.lab

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** The browser polls the display snapshot; tests also wait for its first background refresh. */
internal suspend fun HttpClient.queuePage(): String = withTimeout(5_000) {
    var page = get("/").bodyAsText()
    while (page.contains("Queue snapshot is loading")) {
        delay(10)
        page = get("/").bodyAsText()
    }
    page
}
