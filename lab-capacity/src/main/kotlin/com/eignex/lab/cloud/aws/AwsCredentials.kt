package com.eignex.lab.cloud.aws

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Credentials that sign AWS requests; [expiration] is null for an access key that does not expire. */
data class AwsCredentials(val accessKeyId: String, val secretAccessKey: String, val sessionToken: String? = null,
                          val expiration: Instant? = null) {
    override fun toString() = "AwsCredentials($accessKeyId)"
}

/** The credentials an AWS runtime (a Lambda function) hands its code in the environment. */
class AwsEnvironmentCredentials(private val environment: Map<String, String> = System.getenv()) : () -> AwsCredentials {
    override fun invoke(): AwsCredentials {
        val id = environment["AWS_ACCESS_KEY_ID"]?.takeIf { it.isNotEmpty() }
        val secret = environment["AWS_SECRET_ACCESS_KEY"]?.takeIf { it.isNotEmpty() }
        check(id != null && secret != null) { "no AWS credentials in the environment" }
        return AwsCredentials(id, secret, environment["AWS_SESSION_TOKEN"]?.takeIf { it.isNotEmpty() })
    }
}

/**
 * The credentials of a named profile, resolved as the AWS SDKs do for an explicit profile: a role the profile assumes
 * (`role_arn` with a `source_profile`, resolved the same way, through STS AssumeRole in [region]), else an access key
 * in the shared credentials file (`AWS_SHARED_CREDENTIALS_FILE`, `~/.aws/credentials`), else the profile's
 * `credential_process`, else an access key in the config file (`AWS_CONFIG_FILE`, `~/.aws/config`). A profile the lab
 * cannot resolve itself (SSO, `aws login`, a web identity or a `credential_source`) is resolved by the AWS CLI's
 * `aws configure export-credentials`. An access key is read once; expiring credentials are reused until ten minutes
 * before they expire.
 */
class AwsProfileCredentials(private val profile: String, private val environment: Map<String, String> = System.getenv(),
                            private val clock: Clock = Clock.systemUTC(), private val region: String? = null,
                            private val assumeRole: ((AwsCredentials, AssumeRole) -> AwsCredentials)? = null) : () -> AwsCredentials {
    /** What a profile's `role_arn` asks STS for. */
    data class AssumeRole(val roleArn: String, val sessionName: String, val externalId: String?, val durationSeconds: Int?, val region: String?)

    private var cached: AwsCredentials? = null
    private var refreshAt: Instant = Instant.MIN

    @Synchronized override fun invoke(): AwsCredentials {
        cached?.let { current -> if (current.expiration == null || clock.instant() < refreshAt) return current }
        val fresh = resolve(profile, emptySet())
        // Ten minutes ahead, or halfway through credentials that live shorter, such as an `aws login` session's.
        fresh.expiration?.let { expiration ->
            val now = clock.instant()
            val margin = minOf(REFRESH, Duration.between(now, expiration).dividedBy(2))
            refreshAt = expiration.minus(margin)
        }
        cached = fresh
        return fresh
    }

    private fun resolve(name: String, visited: Set<String>): AwsCredentials {
        check(name !in visited && visited.size < 4) { "AWS profile $profile has a circular or too long source_profile chain" }
        val home = environment["HOME"] ?: System.getProperty("user.home")
        fun file(variable: String, default: String) = Path.of((environment[variable] ?: default).replaceFirst(Regex("^~(?=/|$)"), Regex.escapeReplacement(home)))
        val credentials = ini(file("AWS_SHARED_CREDENTIALS_FILE", "$home/.aws/credentials"))[name]
        val config = ini(file("AWS_CONFIG_FILE", "$home/.aws/config")).let { sections ->
            if (name == "default") sections["default"] ?: sections["profile default"] else sections["profile $name"]
        }
        check(credentials != null || config != null) { "no AWS profile $name" }
        fun option(key: String) = credentials?.get(key)?.takeIf { it.isNotEmpty() } ?: config?.get(key)?.takeIf { it.isNotEmpty() }
        val role = option("role_arn")
        if (role != null && option("source_profile") != null) {
            check(option("mfa_serial") == null) { "AWS profile $name needs an MFA code, which a service cannot supply" }
            val source = option("source_profile")!!
            // A profile that names itself as its source holds the access key the role is assumed with.
            val base = if (source == name) staticKey(credentials) ?: staticKey(config) ?: error("AWS profile $name has no access key")
                       else resolve(source, visited + name)
            val request = AssumeRole(role, option("role_session_name") ?: "eignex-lab-${clock.millis()}", option("external_id"),
                option("duration_seconds")?.toIntOrNull(), region ?: option("region"))
            return (assumeRole ?: ::stsAssumeRole)(base, request)
        }
        if (role == null) {
            staticKey(credentials)?.let { return it }
            (credentials?.get("credential_process") ?: config?.get("credential_process"))?.let { return process(it, name) }
            staticKey(config)?.let { return it }
        }
        // SSO, `aws login`, web identities and credential sources: the AWS CLI resolves them as the SDKs do.
        return process("aws configure export-credentials --profile ${shellQuote(name)} --format process", name)
    }

    private fun staticKey(section: Map<String, String>?): AwsCredentials? {
        val id = section?.get("aws_access_key_id")?.takeIf { it.isNotEmpty() } ?: return null
        val secret = section["aws_secret_access_key"]?.takeIf { it.isNotEmpty() } ?: return null
        return AwsCredentials(id, secret, section["aws_session_token"]?.takeIf { it.isNotEmpty() })
    }

    private fun process(command: String, name: String): AwsCredentials {
        val builder = ProcessBuilder("/bin/sh", "-c", command).redirectError(ProcessBuilder.Redirect.INHERIT)
        environment["PATH"]?.let { builder.environment()["PATH"] = it }
        environment["HOME"]?.let { builder.environment()["HOME"] = it }
        val process = builder.start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(1, TimeUnit.MINUTES) && process.exitValue() == 0) { "credential_process of $name failed" }
        val result = Json.parseToJsonElement(output).jsonObject
        fun field(name: String) = (result[name] as? JsonPrimitive)?.contentOrNull
        check(field("Version") == "1") { "credential_process of $name printed an unsupported version" }
        return AwsCredentials(checkNotNull(field("AccessKeyId")), checkNotNull(field("SecretAccessKey")), field("SessionToken"),
            field("Expiration")?.let { Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(it)) })
    }

    private fun stsAssumeRole(source: AwsCredentials, request: AssumeRole): AwsCredentials {
        val api = AwsApi(request.region ?: "us-east-1", { source }, clock)
        val parameters = linkedMapOf("RoleArn" to request.roleArn, "RoleSessionName" to request.sessionName)
        request.externalId?.let { parameters["ExternalId"] = it }
        request.durationSeconds?.let { parameters["DurationSeconds"] = it.toString() }
        val result = api.query("sts", "AssumeRole", "2011-06-15", parameters).child("AssumeRoleResult")?.child("Credentials")
            ?: error("STS returned no credentials")
        return AwsCredentials(result.text("AccessKeyId")!!, result.text("SecretAccessKey")!!, result.text("SessionToken"),
            result.text("Expiration")?.let { Instant.parse(it) })
    }

    companion object {
        private val REFRESH: Duration = Duration.ofMinutes(10)

        private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

        /** The sections of an INI file as the AWS SDKs read it, option names lowercased; none when it is missing. */
        fun ini(path: Path): Map<String, Map<String, String>> {
            if (!Files.isRegularFile(path)) return emptyMap()
            val sections = linkedMapOf<String, MutableMap<String, String>>()
            var section: MutableMap<String, String>? = null
            for (raw in Files.readAllLines(path)) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
                if (raw[0].isWhitespace()) continue  // a continuation line, such as a nested s3 setting
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = sections.getOrPut(line.substring(1, line.length - 1).trim()) { linkedMapOf() }
                    continue
                }
                val split = line.indexOfFirst { it == '=' || it == ':' }
                if (split <= 0) continue
                section?.put(line.substring(0, split).trim().lowercase(), line.substring(split + 1).trim())
            }
            return sections
        }
    }
}
