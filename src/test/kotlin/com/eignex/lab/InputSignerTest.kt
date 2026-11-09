package com.eignex.lab

import com.eignex.lab.execution.*

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class InputSignerTest {
    private val sha = "a".repeat(64)
    private fun operation(json: String) = InputSigner.operation(Json.parseToJsonElement(json))
    private fun refused(json: String) = assertFailsWith<IllegalArgumentException>(json) { operation(json) }
    private val presigner = S3Presigner("cache", "eu-north-1", { AwsCredentials("AKIDEXAMPLE", "secret", "token/with+chars=") },
        Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC))

    @Test fun `signs the AWS documented presigned GET`() {
        // https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
        val signer = S3Presigner("examplebucket", "us-east-1", { AwsCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY") },
            Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC))
        assertEquals("https://examplebucket.s3.amazonaws.com/test.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z" +
            "&X-Amz-Expires=86400&X-Amz-SignedHeaders=host" +
            "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
            signer.presign("GET", "test.txt", emptyMap(), 86400))
    }

    @Test fun `signs reads exactly as botocore does`() {
        // `aws s3 presign` of AWS CLI 2.36.44 (botocore, the old Python signer's library) with these credentials.
        val credential = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIDEXAMPLE%2F20261109%2Feu-north-1%2Fs3%2Faws4_request"
        val token = "X-Amz-Expires=300&X-Amz-SignedHeaders=host&X-Amz-Security-Token=token%2Fwith%2Bchars%3D"
        val references = listOf(
            Triple("corpus/a b+c", "corpus/a%20b%2Bc", "20261109T140433Z" to "faee0a406f8fcdc0a1f6925aa75e23bab2b0ae298ec283180af71ee461a3f0c4"),
            Triple("input-blobs/$sha", "input-blobs/$sha", "20261109T140434Z" to "daefff608920ad07066bcc1a792eb937a889102d4230c5bfadaaebc8069d3a0f"),
            Triple("corpus/ü~x(1)", "corpus/%C3%BC~x%281%29", "20261109T140434Z" to "212789df622e7ce385039c8e570f102c6ceed223daad7e322ae7d2a5ac5a3ebd"),
            Triple("result-blobs/$sha", "result-blobs/$sha", "20261109T140435Z" to "3b4dcd1a9872e3e77de52e4ccfda3dd9609d6000822c36d7b0e02ecc03768d00"),
        )
        fun query(url: String) = URI(url).rawQuery.split('&').toSet()
        for ((key, path, signature) in references) {
            val (date, expected) = signature
            val at = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX").parse(date, Instant::from)
            val signer = S3Presigner("cache", "eu-north-1", { AwsCredentials("AKIDEXAMPLE", "secret", "token/with+chars=") }, Clock.fixed(at, ZoneOffset.UTC))
            val url = signer.presign("GET", key, emptyMap(), 300)
            val reference = "https://cache.s3.eu-north-1.amazonaws.com/$path?$credential&X-Amz-Date=$date&$token&X-Amz-Signature=$expected"
            assertEquals(reference.substringBefore('?'), url.substringBefore('?'), key)
            assertEquals(query(reference), query(url), key)
        }
    }

    @Test fun `can only publish immutable content hashes`() {
        val put = operation("""{"method": "PUT", "key": "input-blobs/$sha", "sha256": "$sha"}""")
        assertEquals("*", put.headers["If-None-Match"])
        assertEquals(sha, put.headers["x-amz-meta-sha256"])
        assertEquals(java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 0xaa.toByte() }), put.headers["x-amz-checksum-sha256"])
        assertEquals("*", operation("""{"method": "PUT", "key": "corpus-packs/fixture.tar", "sha256": "$sha"}""").headers["If-None-Match"])
        for (key in listOf("corpus/model", "../private", "other/key", "input-blobs/bad")) {
            refused("""{"method": "PUT", "key": "$key", "sha256": "$sha"}""")
        }
        refused("""{"method": "PUT", "key": "input-blobs/$sha"}""")
        refused("""{"method": "PUT", "key": "input-blobs/$sha", "sha256": null}""")
        refused("""{"method": "PUT", "key": "corpus-packs/fixture.tar", "sha256": "${sha.uppercase()}"}""")
    }

    @Test fun `cannot sign other operations or non-input keys`() {
        for (key in listOf("corpus/../../secret", "corpus//model", "builds/private", "corpus\\\\private", "corpus/a\\u0000b",
            "corpus-packs/.tar", "corpus-packs/a/b.tar", "/corpus/model", "corpus/")) {
            refused("""{"method": "GET", "key": "$key"}""")
        }
        for (request in listOf("""{"method": "DELETE", "key": "corpus/model"}""", """{"method": "GET"}""", """{"key": "corpus/model"}""",
            """{"method": "GET", "key": 3}""", """["GET", "corpus/model"]""", "\"GET\"")) {
            refused(request)
        }
        assertEquals(InputSigner.Operation("GET", "corpus/model"), operation("""{"method": "GET", "key": "corpus/model", "bytes": 3}"""))
    }

    @Test fun `build archives and solve outputs are signed by content hash`() {
        val other = "b".repeat(64)
        for (prefix in listOf("build-blobs", "result-blobs")) {
            val put = operation("""{"method": "PUT", "key": "$prefix/$other", "sha256": "$other", "bytes": 12}""")
            assertEquals(listOf("*", other), listOf(put.headers["If-None-Match"], put.headers["x-amz-meta-sha256"]))
            assertEquals(InputSigner.Operation("HEAD", "$prefix/$other"), operation("""{"method": "HEAD", "key": "$prefix/$other"}"""))
            assertEquals(InputSigner.Operation("GET", "$prefix/$other"), operation("""{"method": "GET", "key": "$prefix/$other"}"""))
            for ((key, digest) in listOf("$prefix/$other" to "c".repeat(64), "$prefix/bad" to other, "$prefix/$other/x" to other)) {
                refused("""{"method": "PUT", "key": "$key", "sha256": "$digest"}""")
            }
        }
    }

    @Test fun `a write signs the headers it must send and the session token`() {
        val response = Json.decodeFromString<SignedInput>(
            InputSigner.response(operation("""{"method": "PUT", "key": "result-blobs/$sha", "sha256": "$sha"}"""), presigner))
        val url = URI(response.url)
        assertEquals("cache.s3.eu-north-1.amazonaws.com", url.host)
        assertEquals("/result-blobs/$sha", url.rawPath)
        val query = url.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("host%3Bif-none-match%3Bx-amz-checksum-sha256%3Bx-amz-meta-sha256", query["X-Amz-SignedHeaders"])
        assertEquals("AKIDEXAMPLE%2F20261109%2Feu-north-1%2Fs3%2Faws4_request", query["X-Amz-Credential"])
        assertEquals(listOf("300", "20261109T120000Z", "token%2Fwith%2Bchars%3D"),
            listOf(query["X-Amz-Expires"], query["X-Amz-Date"], query["X-Amz-Security-Token"]))
        assertTrue(query.getValue("X-Amz-Signature").matches(Regex("[0-9a-f]{64}")))
        assertEquals(listOf("If-None-Match", "x-amz-meta-sha256", "x-amz-checksum-sha256"), response.headers.keys.toList())
        assertEquals("host", Regex("X-Amz-SignedHeaders=([^&]*)").find(presigner.presign("GET", "corpus/a b+c", emptyMap(), 300))!!.groupValues[1])
        assertTrue(presigner.presign("GET", "corpus/a b+c", emptyMap(), 300).startsWith("https://cache.s3.eu-north-1.amazonaws.com/corpus/a%20b%2Bc?"))
    }

    @Test fun `the coordinator's client gets URLs and its malformed requests are refused`() {
        val failing = InputSigner.serve(0) { error("no credentials") }
        val server = InputSigner.serve(0) { InputSigner.response(it, presigner) }
        try {
            val signer = URI("http://127.0.0.1:${server.address.port}/")
            val signed = signInput(signer, InputSignature("HEAD", "input-blobs/$sha"))
            assertEquals(emptyMap(), signed.headers)
            assertTrue(signed.url.startsWith("https://cache.s3.eu-north-1.amazonaws.com/input-blobs/$sha?"))
            val put = signInput(signer, InputSignature("PUT", "result-blobs/$sha", sha, bytes = 42))
            assertEquals(sha, put.headers["x-amz-meta-sha256"])
            val client = HttpClient.newHttpClient()
            fun status(method: String, path: String, body: String) = client.send(HttpRequest.newBuilder(signer.resolve(path))
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()
            assertEquals(400, status("POST", "/", """{"method": "PUT", "key": "input-blobs/$sha"}"""))
            assertEquals(400, status("POST", "/", "not json"))
            assertEquals(400, status("POST", "/", ""))
            assertEquals(400, status("POST", "/", "x".repeat(8193)))
            assertEquals(400, status("POST", "/other", """{"method": "GET", "key": "corpus/model"}"""))
            assertEquals(400, status("POST", "/?q", """{"method": "GET", "key": "corpus/model"}"""))
            assertEquals(501, status("GET", "/", ""))
            assertEquals(503, status("POST", "http://127.0.0.1:${failing.address.port}/", """{"method": "GET", "key": "corpus/model"}"""))
            assertEquals(400, status("POST", "http://127.0.0.1:${failing.address.port}/", """{"method": "GET", "key": "other"}"""))
        } finally {
            server.stop(0); failing.stop(0)
        }
    }

    @Test fun `answers a worker's burst of concurrent checks together`() {
        val burst = 8
        val arrived = CountDownLatch(burst)
        // Each answer waits until the whole burst is being handled: a listener that queued or refused any would time out.
        val server = InputSigner.serve(0) { operation ->
            arrived.countDown(); check(arrived.await(10, TimeUnit.SECONDS)); InputSigner.response(operation, presigner)
        }
        val pool = Executors.newFixedThreadPool(burst)
        try {
            val signer = URI("http://127.0.0.1:${server.address.port}/")
            val answers = (1..burst).map { pool.submit<SignedInput> { signInput(signer, InputSignature("HEAD", "input-blobs/$sha")) } }
            assertEquals(burst, answers.count { it.get(20, TimeUnit.SECONDS).url.startsWith("https://") })
        } finally {
            pool.shutdownNow(); server.stop(0)
        }
    }

    @Test fun `credentials come from the named profile as the AWS SDKs read it`() {
        val home = Files.createTempDirectory("aws-home")
        try {
            Files.createDirectories(home.resolve(".aws"))
            Files.writeString(home.resolve(".aws/credentials"), """
                [default]
                aws_access_key_id = DEFAULT
                aws_secret_access_key = default-secret
                # a comment
                [lab-inputs]
                AWS_ACCESS_KEY_ID=AKIDLAB
                aws_secret_access_key: lab/secret+key
                """.trimIndent())
            Files.writeString(home.resolve(".aws/config"), """
                [profile process]
                region = eu-north-1
                credential_process = printf '{"Version": 1, "AccessKeyId": "AKIDPROC", "SecretAccessKey": "s", "SessionToken": "t", "Expiration": "2099-01-01T00:00:00Z"}'
                s3 =
                  addressing_style = virtual
                [profile configured]
                aws_access_key_id = AKIDCONFIG
                aws_secret_access_key = c
                """.trimIndent())
            val environment = mapOf("HOME" to home.toString())
            assertEquals(AwsCredentials("AKIDLAB", "lab/secret+key"), AwsProfileCredentials("lab-inputs", environment).invoke())
            assertEquals(AwsCredentials("AKIDCONFIG", "c"), AwsProfileCredentials("configured", environment).invoke())
            assertEquals(AwsCredentials("AKIDPROC", "s", "t", Instant.parse("2099-01-01T00:00:00Z")), AwsProfileCredentials("process", environment).invoke())
            assertFailsWith<IllegalStateException> { AwsProfileCredentials("missing", environment).invoke() }
            val moved = home.resolve("elsewhere")
            Files.move(home.resolve(".aws/credentials"), moved)
            assertEquals("DEFAULT", AwsProfileCredentials("default", environment + ("AWS_SHARED_CREDENTIALS_FILE" to moved.toString())).invoke().accessKeyId)
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
