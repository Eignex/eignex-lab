package com.eignex.lab.capacity

import com.eignex.lab.capacity.CapacityAdapter.optional
import com.eignex.lab.capacity.CapacityAdapter.text
import com.eignex.lab.cloud.CapacityRejected
import com.eignex.lab.cloud.ProviderError
import com.eignex.lab.cloud.aws.AutoScaling
import com.eignex.lab.cloud.aws.AwsApi
import com.eignex.lab.cloud.aws.AwsCapacityApi
import com.eignex.lab.cloud.aws.AwsCredentials
import com.eignex.lab.cloud.aws.AwsEnvironmentCredentials
import com.eignex.lab.cloud.aws.AwsProfileCredentials
import com.eignex.lab.cloud.aws.Ec2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The AWS adapter (`capacity-adapter aws CONFIG`), whose role (`adapter_role_arn`) may only describe the pools' groups
 * and instances and invoke their renewal functions. The configuration names the `region`, the AWS `profile` (its
 * credentials as [AwsProfileCredentials] resolves them), the `deployment`, the `inventoryFile` to publish, the
 * `capacityTokenFile`, the `coordinator` and optionally the lab's `contract`, and either one pool (`pool`, `group`,
 * `renewFunction`) or a fleet of them under `pools`, each entry overriding the shared settings.
 */
object AwsCapacityAdapter {
    /** Renewal failures by pool: only pool names and bounded codes, never grants, credentials or provider bodies. */
    class RenewalError(message: String) : RuntimeException(message)

    /** A renewal function's synchronous invocation: its error type when it failed, and its output. */
    fun interface Functions {
        fun invoke(function: String, payload: ByteArray): Pair<String?, ByteArray>
    }

    /** How long a renewal invocation may take: a cold Java function starts within a few seconds. */
    val INVOKE_TIMEOUT: Duration = Duration.ofSeconds(30)

    /** The pool definitions of [config]: each `pools` entry over the shared settings, or the one pool it names. */
    fun definitions(config: JsonObject): List<JsonObject> {
        val pools = (config["pools"] as? JsonArray)?.map { JsonObject(config + (it as JsonObject)) } ?: listOf(config)
        if (pools.isEmpty() || pools.map { it.text("pool") }.toSet().size != pools.size || pools.map { it.text("group") }.toSet().size != pools.size) {
            throw CapacityRejected("configure distinct pools and groups")
        }
        // With the lab's pool contract (`eignex-lab capacity-export`), every pool configured here must be one of its pools.
        for (pool in pools) CapacityAdapter.checkContract(config.optional("contract"), pool.text("pool"))
        return pools
    }

    /**
     * Pool [pool]'s inventory: the members of its [group], which must be [deployment]'s, each with its provider launch
     * time when EC2 still describes it, booting placeholders up to the desired capacity, and the latest failed scaling
     * activity's message, observed at [at] milliseconds.
     */
    fun inventory(asg: AutoScaling, group: String, deployment: String, pool: String, ec2: Ec2?, at: () -> Long = System::currentTimeMillis): JsonObject {
        val value = asg.group(group) ?: throw CapacityRejected("pool group missing")
        if (value.tags["lab:deployment"] != deployment || value.tags["lab:pool"] != pool) throw CapacityRejected("pool ownership mismatch")
        val instances = value.instances.map { it.id to it.lifecycleState.startsWith("Terminating") }
        val activity = asg.latestActivity(group)?.takeIf { it.statusCode == "Failed" }
        val failure = activity?.let { it.statusMessage?.takeIf(String::isNotEmpty) ?: "capacity unavailable" }
        val observed = CapacityAdapter.observation(pool, instances, value.desiredCapacity, failure, at())
        val errors = if (activity?.startedAt != null && failure?.contains(Regex("\\bUnfulfillableCapacity\\b")) == true)
            buildJsonObject { put(pool, buildJsonObject { put("code", "UnfulfillableCapacity"); put("at", activity.startedAt) }) }
        else JsonObject(emptyMap())
        val result = JsonObject(observed + ("capacityErrors" to errors))
        if (ec2 == null || instances.isEmpty()) return result
        // ASG membership can lag Spot termination. Filters omit vanished IDs instead of rejecting the fleet.
        val launched = instances.map { it.first }.chunked(100).flatMap { ec2.describeAmong(it) }.associate { it.id to it.launchedAt }
        return JsonObject(result + ("instances" to JsonArray((result.getValue("instances") as JsonArray).map { item ->
            val fields = item as JsonObject
            val id = (fields.getValue("id") as JsonPrimitive).content
            if (id in launched) JsonObject(fields + ("launchedAt" to JsonPrimitive(launched.getValue(id)))) else fields
        })))
    }

    /**
     * Publish every configured pool's inventory as one, observed concurrently so a larger fleet does not age past the
     * coordinator's freshness bound; a pool that cannot be observed publishes nothing.
     */
    fun publish(config: JsonObject, observe: (JsonObject) -> JsonObject) {
        val started = System.nanoTime()
        val observations = CapacityAdapter.parallel(definitions(config), observe)
        val value = CapacityAdapter.merge(observations)
        if (System.nanoTime() - started > CapacityAdapter.FRESHNESS_MS * 1_000_000) throw CapacityRejected("inventory request exceeded freshness bound")
        CapacityAdapter.writeAtomic(Path.of(config.text("inventoryFile")), value)
    }

    /**
     * Ask the coordinator for each pool's grant and hand it to the pool's renewal function, concurrently and
     * independently: one pool's failure never holds another's renewal. [grant] fetches a pool's grant with the key.
     */
    fun renew(config: JsonObject, functions: Functions,
              grant: (String, String) -> JsonElement = { pool, key -> CapacityAdapter.grant(config.text("coordinator"), pool, key) }) {
        val pools = definitions(config)
        val key = Files.readString(Path.of(config.text("capacityTokenFile"))).trim()
        val failed = CapacityAdapter.parallel(pools) { pool ->
            val name = pool.text("pool")
            try {
                val (error, output) = functions.invoke(pool.text("renewFunction"), grant(name, key).toString().toByteArray())
                if (error == null) null else name to errorType(output)
            } catch (error: Exception) {
                name to CapacityAdapter.code(error)
            }
        }.filterNotNull()
        if (failed.isNotEmpty()) throw RenewalError("capacity renewal held for " + failed.joinToString(", ") { (pool, reason) -> "$pool=$reason" })
    }

    /** A failed function's `errorType`, when it is a bounded name; else `provider_error`. */
    private fun errorType(output: ByteArray): String {
        val type = try {
            ((Json.parseToJsonElement(output.toString(Charsets.UTF_8)) as? JsonObject)?.get("errorType") as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (_: IllegalArgumentException) {
            null
        }
        return type?.takeIf { ProviderError.CODE.matches(it) } ?: "provider_error"
    }

    /** What is safe to log of [error]: its bounded code, and the message of this adapter's own refusals. */
    fun describe(error: Throwable): String = when (error) {
        is RenewalError, is CapacityRejected -> "${error.javaClass.simpleName}: ${error.message}"
        else -> CapacityAdapter.code(error)
    }

    /** The adapter's credentials: the configured `profile`, else `AWS_PROFILE`, else environment keys, else `default`. */
    fun credentials(config: JsonObject, environment: Map<String, String> = System.getenv()): () -> AwsCredentials {
        val region = config.text("region")
        val profile = config.optional("profile") ?: environment["AWS_PROFILE"]
        if (profile == null && !environment["AWS_ACCESS_KEY_ID"].isNullOrEmpty()) return AwsEnvironmentCredentials(environment)
        return AwsProfileCredentials(profile ?: "default", environment, region = region)
    }

    fun run(config: JsonObject, once: Boolean): Int {
        val api = AwsApi(config.text("region"), credentials(config))
        val provider = AwsCapacityApi(api)
        val functions = Functions { function, payload -> provider.invoke(function, payload, INVOKE_TIMEOUT) }
        return CapacityAdapter.loop("AWS", once,
            { publish(config) { pool -> inventory(provider, pool.text("group"), pool.text("deployment"), pool.text("pool"), provider) } },
            { renew(config, functions) }, ::describe)
    }
}
