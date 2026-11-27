package com.eignex.lab

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Whether [condition] holds within [timeoutMs], checked every [pollMs] and once more at the deadline. */
internal fun eventually(timeoutMs: Long = 10_000, pollMs: Long = 20, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(pollMs)
    }
    return condition()
}

/** The browser polls the display snapshot; tests also wait for its first background refresh. */
internal suspend fun HttpClient.queuePage(): String = withTimeout(5_000) {
    var page = get("/").bodyAsText()
    while (page.contains("Queue snapshot is loading")) {
        delay(10)
        page = get("/").bodyAsText()
    }
    page
}
