package com.eignex.lab.capacity

import com.eignex.lab.capacity.CapacityAdapter.optional
import com.eignex.lab.capacity.CapacityAdapter.text
import com.eignex.lab.cloud.ProviderError
import com.eignex.lab.cloud.ProviderHttp
import com.eignex.lab.cloud.gcp.GcpCloud
import com.eignex.lab.cloud.gcp.GcpRestCloud
import com.eignex.lab.cloud.gcp.GoogleTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The GCP adapter (`capacity-adapter gcp CONFIG`): Application Default Credentials impersonating the adapter's
 * service account (`serviceAccount`), never a downloaded key. The configuration names the pool's `PROJECT`, `ZONE`,
 * `GROUP`, `POOL`, `OWNERSHIP` and `BUCKET` (the module's outputs), its `renewUrl`, the `inventoryFile` to publish,
 * the `capacityTokenFile`, the `coordinator`, and optionally the lab's `contract` with the pool's `MAX_NODES`.
 */
object GcpCapacityAdapter {
    /** The pool's inventory as the managed group lists it, observed at [at] milliseconds. */
    fun inventory(cloud: GcpCloud, at: () -> Long = System::currentTimeMillis): JsonObject {
        val group = cloud.owned()
        val items = cloud.managedInstances()
        val instances = items.mapIndexed { index, item ->
            fun text(name: String) = (item[name] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
            val id = (text("instance") ?: ("booting-" + (text("name") ?: index.toString()))).removePrefix("https://www.googleapis.com/compute/v1/")
            id to (text("currentAction") in setOf("DELETING", "ABANDONING"))
        }
        val errors = items.flatMap { item ->
            val attempt = item["lastAttempt"] as? JsonObject
            ((attempt?.get("errors") as? JsonObject)?.get("errors") as? JsonArray).orEmpty().map { error ->
                val message = ((error as JsonObject)["message"] as? JsonPrimitive)?.content ?: "capacity unavailable"
                message.take(500)
            }
        }
        return CapacityAdapter.observation(cloud.config.getValue("POOL"), instances, group.getValue("targetSize").jsonPrimitive.content.toInt(),
            errors.joinToString("; "), at())
    }

    fun publish(cloud: GcpCloud, inventoryFile: Path) {
        val started = System.nanoTime()
        val value = inventory(cloud)
        check(System.nanoTime() - started <= CapacityAdapter.FRESHNESS_MS * 1_000_000) { "inventory freshness bound exceeded" }
        CapacityAdapter.writeAtomic(inventoryFile, value)
    }

    /** Ask the coordinator for the pool's grant and post it to the renewal function with a fresh identity token. */
    fun renew(config: JsonObject, idToken: () -> String, http: ProviderHttp = ProviderHttp.shared) {
        val key = Files.readString(Path.of(config.text("capacityTokenFile"))).trim()
        val grant = CapacityAdapter.grant(config.text("coordinator"), config.text("POOL"), key)
        val token = idToken()
        val response = http.exchange("POST", URI(config.text("renewUrl")), grant.toString().toByteArray(), Duration.ofSeconds(10), attemptsOnce) {
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        }
        if (response.status !in 200..299) throw ProviderError(null, response.status)
        Json.parseToJsonElement(response.text)
    }

    /** Renewal posts once per round; the next round, ten seconds later, is its retry. */
    private val attemptsOnce = { _: com.eignex.lab.cloud.ProviderResponse -> false }

    fun run(config: JsonObject, once: Boolean): Int {
        val pool = config.text("POOL")
        // With the lab's pool contract (`eignex-lab capacity-export`), this pool and its limit must be the lab's.
        config.optional("contract")?.let { contract ->
            CapacityAdapter.checkContract(contract, pool, mapOf("maxNodes" to config.text("MAX_NODES").toInt()))
        }
        val tokens = GoogleTokens.Impersonated(GoogleTokens.application(), config.text("serviceAccount"))
        val cloud = GcpRestCloud(config.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() }, tokens)
        val inventoryFile = Path.of(config.text("inventoryFile"))
        return CapacityAdapter.loop("GCP", once, { publish(cloud, inventoryFile) }, { renew(config, { tokens.idToken(config.text("renewUrl")) }) }) {
            it.javaClass.simpleName
        }
    }
}
