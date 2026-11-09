package com.eignex.lab.cloud.gcp

import com.eignex.lab.cloud.CapacityRejected
import com.google.cloud.functions.HttpFunction
import com.google.cloud.functions.HttpRequest
import com.google.cloud.functions.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The pool a function serves, from the environment Terraform gives it (`deploy/infra/gcp/main.tf`), as its runtime identity. */
internal fun runtimeCloud(): GcpCloud = GcpRestCloud(System.getenv(), GoogleTokens.Metadata())

private fun HttpResponse.json(status: Int, value: JsonObject) {
    setStatusCode(status)
    setContentType("application/json")
    writer.write(value.toString())
}

/**
 * Entry point `com.eignex.lab.cloud.gcp.GcpRenewFunction`: renew the pool's lease for the coordinator grant the Mac
 * adapter posts, answering 403 for a rejected grant or an unowned group.
 */
class GcpRenewFunction : HttpFunction {
    override fun service(request: HttpRequest, response: HttpResponse) {
        val grant: JsonElement = try {
            Json.parseToJsonElement(request.reader.readText())
        } catch (_: IllegalArgumentException) {
            JsonNull
        }
        val result = try {
            val cloud = runtimeCloud()
            GcpCapacity.renew(grant, cloud, cloud.signingKey())
        } catch (_: CapacityRejected) {
            return response.json(403, JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive("capacity grant or ownership rejected"))))
        }
        response.json(200, result)
    }
}

/** Entry point `com.eignex.lab.cloud.gcp.GcpExpireFunction`: the scheduled independent expiry of the pool's lease. */
class GcpExpireFunction : HttpFunction {
    override fun service(request: HttpRequest, response: HttpResponse) {
        response.json(200, GcpCapacity.expire(runtimeCloud()))
    }
}
