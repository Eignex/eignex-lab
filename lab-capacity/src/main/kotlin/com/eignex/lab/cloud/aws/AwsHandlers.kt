package com.eignex.lab.cloud.aws

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.InputStream
import java.io.OutputStream

/**
 * The pool a Lambda function serves, from the environment Terraform gives it (`deploy/infra/aws/main.tf`): `GROUP`,
 * `TABLE`, `POOL`, `DEPLOYMENT`, `MAX_NODES` (renewal only) and the runtime's region and credentials.
 */
internal fun lambdaPool(environment: Map<String, String> = System.getenv()): Pair<AwsPool, AwsCapacityApi> {
    val api = AwsCapacityApi(AwsApi(environment.getValue("AWS_REGION"), AwsEnvironmentCredentials(environment)))
    val pool = AwsPool(environment.getValue("GROUP"), environment.getValue("DEPLOYMENT"), environment.getValue("POOL"),
        environment["MAX_NODES"]?.toInt() ?: 0, api, api, api.leases(environment.getValue("TABLE")))
    return pool to api
}

/**
 * `com.eignex.lab.cloud.aws.AwsRenewHandler::handleRequest`: renew the pool's lease for the coordinator grant the Mac
 * adapter invokes it with. Only the adapter's role may invoke it. A rejected grant fails the invocation with its
 * error type, which the adapter reports.
 */
class AwsRenewHandler : RequestStreamHandler {
    override fun handleRequest(input: InputStream, output: OutputStream, context: Context?) {
        val grant = event(input)
        val (pool, api) = lambdaPool()
        val key = api.secret(System.getenv("SIGNING_SECRET"))
        output.write(pool.renew(grant, key).toString().toByteArray())
    }
}

/** `com.eignex.lab.cloud.aws.AwsExpireHandler::handleRequest`: the scheduled independent expiry of the pool's lease. */
class AwsExpireHandler : RequestStreamHandler {
    override fun handleRequest(input: InputStream, output: OutputStream, context: Context?) {
        input.readAllBytes()
        output.write(lambdaPool().first.expire().toString().toByteArray())
    }
}

/** The invocation's JSON event, or JSON null when it is not JSON (which no grant is). */
private fun event(input: InputStream): JsonElement = try {
    Json.parseToJsonElement(input.readAllBytes().toString(Charsets.UTF_8))
} catch (_: IllegalArgumentException) {
    JsonNull
}
