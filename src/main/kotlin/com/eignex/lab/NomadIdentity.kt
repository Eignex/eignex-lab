package com.eignex.lab

import kotlinx.serialization.json.*
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Validates against the configured cluster's keys; never follows token-supplied key URLs. */
class NomadIdentity(private val keys: () -> JsonObject, private val namespace: String = "default") {
    fun verify(jwt: String, session: ExecutionSession, allocation: String, at: Long = now()) {
        require(jwt.length <= 16384)
        val parts = jwt.split('.'); require(parts.size == 3)
        fun bytes(value: String) = Base64.getUrlDecoder().decode(value)
        val header = Json.parseToJsonElement(String(bytes(parts[0]))).jsonObject
        val claims = Json.parseToJsonElement(String(bytes(parts[1]))).jsonObject
        val algorithm = header.getValue("alg").jsonPrimitive.content
        require(algorithm in setOf("RS256", "EdDSA"))
        val key = keys().getValue("keys").jsonArray.map { it.jsonObject }.single { it["kid"] == header["kid"] && header["kid"] != null }
        val publicKey = when (algorithm) {
            "RS256" -> {
                require(key["kty"]?.jsonPrimitive?.content == "RSA")
                KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, bytes(key.getValue("n").jsonPrimitive.content)), BigInteger(1, bytes(key.getValue("e").jsonPrimitive.content))))
            }
            else -> {
                require(key["kty"]?.jsonPrimitive?.content == "OKP" && key["crv"]?.jsonPrimitive?.content == "Ed25519")
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + bytes(key.getValue("x").jsonPrimitive.content)))
            }
        }
        val verifier = Signature.getInstance(if (algorithm == "RS256") "SHA256withRSA" else "Ed25519")
        verifier.initVerify(publicKey); verifier.update("${parts[0]}.${parts[1]}".toByteArray())
        require(verifier.verify(bytes(parts[2]))) { "invalid workload signature" }
        val seconds = at / 1000
        require(claims.getValue("exp").jsonPrimitive.long > seconds && (claims["nbf"]?.jsonPrimitive?.long ?: 0) <= seconds + 5)
        val audience = claims.getValue("aud")
        require(when (audience) {
            is JsonArray -> audience.any { it.jsonPrimitive.content == "eignex-lab-worker" }
            is JsonPrimitive -> audience.content == "eignex-lab-worker"
            else -> false
        }) { "workload audience mismatch" }
        require(claims["nomad_namespace"]?.jsonPrimitive?.content == namespace)
        require(claims["nomad_job_id"]?.jsonPrimitive?.content == session.nomadJobId && claims["nomad_task"]?.jsonPrimitive?.content == "worker")
        require(claims["nomad_allocation_id"]?.jsonPrimitive?.content == allocation) { "workload allocation mismatch" }
    }
}
