package com.eignex.lab.cloud

import com.eignex.lab.cloud.aws.AsgMember
import com.eignex.lab.cloud.aws.AwsApi
import com.eignex.lab.cloud.aws.AwsCapacityApi
import com.eignex.lab.cloud.aws.AwsCredentials
import com.eignex.lab.cloud.aws.AwsProfileCredentials
import com.eignex.lab.cloud.aws.AwsSigner
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The AWS wire: SigV4, the query, JSON and Lambda protocols, and what the capacity calls send and read. */
class AwsApiTest {
    private val clock = Clock.fixed(Instant.parse("2015-08-30T12:36:00Z"), ZoneOffset.UTC)
    private val example = AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")

    private fun form(body: String) = body.split('&').associate { part ->
        URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(part.substringAfter('='), Charsets.UTF_8)
    }

    @Test fun `signatures match the AWS Signature Version 4 test suite`() {
        val vanilla = AwsSigner.sign(example, "us-east-1", "service", "GET", "example.amazonaws.com", "/", emptyMap(), ByteArray(0), clock)
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, " +
            "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", vanilla["authorization"])
        assertEquals("20150830T123600Z", vanilla["x-amz-date"])
        val post = AwsSigner.sign(example, "us-east-1", "service", "POST", "example.amazonaws.com", "/", emptyMap(), ByteArray(0), clock)
        assertTrue(post.getValue("authorization").endsWith("Signature=5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b"))
        val session = AwsSigner.sign(example.copy(sessionToken = "token"), "us-east-1", "service", "GET", "example.amazonaws.com", "/", emptyMap(), ByteArray(0), clock)
        assertEquals("token", session["x-amz-security-token"])
        assertTrue("SignedHeaders=host;x-amz-date;x-amz-security-token," in session.getValue("authorization"))
    }

    private val group = """
        <DescribeAutoScalingGroupsResponse xmlns="http://autoscaling.amazonaws.com/doc/2011-01-01/">
          <DescribeAutoScalingGroupsResult><AutoScalingGroups><member>
            <AutoScalingGroupName>group</AutoScalingGroupName>
            <MixedInstancesPolicy><LaunchTemplate><Overrides><member><InstanceType>m7g.large</InstanceType></member></Overrides></LaunchTemplate></MixedInstancesPolicy>
            <MinSize>0</MinSize><MaxSize>4</MaxSize><DesiredCapacity>2</DesiredCapacity>
            <Instances>
              <member><InstanceId>i-1</InstanceId><LifecycleState>InService</LifecycleState><ProtectedFromScaleIn>true</ProtectedFromScaleIn></member>
              <member><InstanceId>i-2</InstanceId><LifecycleState>Terminating:Wait</LifecycleState><ProtectedFromScaleIn>false</ProtectedFromScaleIn></member>
              <member><InstanceId>i-3</InstanceId><LifecycleState>Pending</LifecycleState></member>
            </Instances>
            <Tags>
              <member><ResourceId>group</ResourceId><Key>lab:deployment</Key><Value>lab</Value><PropagateAtLaunch>true</PropagateAtLaunch></member>
              <member><ResourceId>group</ResourceId><Key>lab:pool</Key><Value>general</Value><PropagateAtLaunch>true</PropagateAtLaunch></member>
            </Tags>
          </member></AutoScalingGroups></DescribeAutoScalingGroupsResult>
        </DescribeAutoScalingGroupsResponse>""".trimIndent()

    @Test fun `auto scaling groups and activities are read from the query protocol`() {
        val http = RecordingHttp { _, _, body ->
            when (form(body)["Action"]) {
                "DescribeAutoScalingGroups" -> ok(group)
                else -> ok("""<DescribeScalingActivitiesResponse><DescribeScalingActivitiesResult><Activities><member>
                    <StatusCode>Failed</StatusCode><StatusMessage>We currently do not have sufficient capacity</StatusMessage><StartTime>2026-11-11T16:37:15.702Z</StartTime>
                    </member></Activities></DescribeScalingActivitiesResult></DescribeScalingActivitiesResponse>""")
            }
        }
        val api = AwsCapacityApi(AwsApi("eu-north-1", { example }, clock, http))
        val value = api.group("group")!!
        assertEquals(Triple(0, 4, 2), Triple(value.minSize, value.maxSize, value.desiredCapacity))
        assertEquals(listOf(AsgMember("i-1", "InService", true), AsgMember("i-2", "Terminating:Wait", false), AsgMember("i-3", "Pending", null)), value.instances)
        assertEquals(mapOf("lab:deployment" to "lab", "lab:pool" to "general"), value.tags)
        val (method, uri, body) = http.requests.single()
        assertEquals("POST" to "https://autoscaling.eu-north-1.amazonaws.com/", method to uri.toString())
        assertEquals(mapOf("Action" to "DescribeAutoScalingGroups", "Version" to "2011-01-01", "AutoScalingGroupNames.member.1" to "group"), form(body))
        val headers = http.headers.single()
        assertTrue(headers.getValue("authorization").startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/eu-north-1/autoscaling/aws4_request, " +
            "SignedHeaders=content-type;host;x-amz-date, "))
        val activity = api.latestActivity("group")!!
        assertEquals("We currently do not have sufficient capacity", activity.statusMessage)
        assertEquals(Instant.parse("2026-11-11T16:37:15.702Z").toEpochMilli(), activity.startedAt)
        assertEquals(mapOf("Action" to "DescribeScalingActivities", "Version" to "2011-01-01", "AutoScalingGroupName" to "group", "MaxRecords" to "1"),
            form(http.requests.last().third))
        http.answer = { _, _, _ -> ok("<DescribeAutoScalingGroupsResponse><DescribeAutoScalingGroupsResult><AutoScalingGroups/></DescribeAutoScalingGroupsResult></DescribeAutoScalingGroupsResponse>") }
        assertNull(api.group("group"))
    }

    @Test fun `fleet inventory batches fifty group names per provider request`() {
        val member = group.substringAfter("<AutoScalingGroups>").substringBefore("</AutoScalingGroups>")
        val http = RecordingHttp { _, _, body ->
            val names = form(body).filterKeys { it.startsWith("AutoScalingGroupNames.member.") }.values
            ok("<DescribeAutoScalingGroupsResponse><DescribeAutoScalingGroupsResult><AutoScalingGroups>" +
                names.joinToString("") { member.replace("<AutoScalingGroupName>group</AutoScalingGroupName>", "<AutoScalingGroupName>$it</AutoScalingGroupName>") } +
                "</AutoScalingGroups></DescribeAutoScalingGroupsResult></DescribeAutoScalingGroupsResponse>")
        }
        val api = AwsCapacityApi(AwsApi("eu-north-1", { example }, clock, http))
        val names = (1..20).map { "group-$it" }
        assertEquals(names.toSet(), api.groups(names).keys)
        assertEquals(1, http.requests.size)
        assertEquals(20, form(http.requests.single().third).keys.count { it.startsWith("AutoScalingGroupNames.member.") })
        assertEquals(51, api.groups((1..51).map { "group-$it" }).size)
        assertEquals(3, http.requests.size)
    }

    @Test fun `group changes send exactly the asked fields`() {
        val http = RecordingHttp { _, _, _ -> ok("<Response/>") }
        val api = AwsCapacityApi(AwsApi("eu-north-1", { example }, clock, http))
        api.update("group", minSize = 0, desiredCapacity = 0, maxSize = 0)
        assertEquals(mapOf("Action" to "UpdateAutoScalingGroup", "Version" to "2011-01-01", "AutoScalingGroupName" to "group",
            "MinSize" to "0", "DesiredCapacity" to "0", "MaxSize" to "0"), form(http.requests.last().third))
        api.update("group", minSize = 0, maxSize = 4)
        assertEquals(mapOf("Action" to "UpdateAutoScalingGroup", "Version" to "2011-01-01", "AutoScalingGroupName" to "group",
            "MinSize" to "0", "MaxSize" to "4"), form(http.requests.last().third))
        api.unprotect("group", listOf("i-1", "i-2"))
        assertEquals(mapOf("Action" to "SetInstanceProtection", "Version" to "2011-01-01", "AutoScalingGroupName" to "group",
            "ProtectedFromScaleIn" to "false", "InstanceIds.member.1" to "i-1", "InstanceIds.member.2" to "i-2"), form(http.requests.last().third))
        api.replace("i-1")
        assertEquals(mapOf("Action" to "TerminateInstanceInAutoScalingGroup", "Version" to "2011-01-01", "InstanceId" to "i-1",
            "ShouldDecrementDesiredCapacity" to "false"), form(http.requests.last().third))
        api.terminate(listOf("i-1", "i-2"))
        assertEquals("https://ec2.eu-north-1.amazonaws.com/", http.requests.last().second.toString())
        assertEquals(mapOf("Action" to "TerminateInstances", "Version" to "2016-11-15", "InstanceId.1" to "i-1", "InstanceId.2" to "i-2"),
            form(http.requests.last().third))
    }

    private fun instances(next: String? = null, vararg ids: String) = """
        <DescribeInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
          <requestId>r</requestId>
          <reservationSet>${ids.joinToString("") { id -> """
            <item><reservationId>r-1</reservationId><groupSet><item><groupId>sg-1</groupId></item></groupSet><instancesSet><item>
              <instanceId>$id</instanceId><instanceState><code>16</code><name>running</name></instanceState>
              <launchTime>2026-11-09T10:00:00.000Z</launchTime>
              <networkInterfaceSet><item><networkInterfaceId>eni-1</networkInterfaceId><tagSet><item><key>lab:pool</key><value>wrong</value></item></tagSet></item></networkInterfaceSet>
              <tagSet><item><key>lab:deployment</key><value>lab</value></item><item><key>lab:pool</key><value>general</value></item></tagSet>
            </item></instancesSet></item>""" }}
          </reservationSet>${next?.let { "<nextToken>$it</nextToken>" } ?: ""}
        </DescribeInstancesResponse>""".trimIndent()

    @Test fun `instances are read across pages from direct children only`() {
        val http = RecordingHttp { _, _, body -> if (form(body)["NextToken"] == null) ok(instances("page-2", "i-1")) else ok(instances(null, "i-2")) }
        val api = AwsCapacityApi(AwsApi("eu-north-1", { example }, clock, http))
        val found = api.tagged(linkedMapOf("lab:deployment" to "lab", "lab:pool" to "general"), listOf("pending", "running"))
        assertEquals(listOf("i-1", "i-2"), found.map { it.id })
        assertEquals(Instant.parse("2026-11-09T10:00:00Z").toEpochMilli(), found[0].launchedAt)
        assertEquals("running", found[0].state)
        assertEquals(mapOf("lab:deployment" to "lab", "lab:pool" to "general"), found[0].tags)
        assertEquals(mapOf("Action" to "DescribeInstances", "Version" to "2016-11-15", "Filter.1.Name" to "tag:lab:deployment", "Filter.1.Value.1" to "lab",
            "Filter.2.Name" to "tag:lab:pool", "Filter.2.Value.1" to "general", "Filter.3.Name" to "instance-state-name",
            "Filter.3.Value.1" to "pending", "Filter.3.Value.2" to "running"), form(http.requests[0].third))
        assertEquals("page-2", form(http.requests[1].third)["NextToken"])
        api.describeAmong(listOf("i-1", "i-2"))
        assertEquals(mapOf("Filter.1.Name" to "instance-id", "Filter.1.Value.1" to "i-1", "Filter.1.Value.2" to "i-2"),
            form(http.requests.last().third).filterKeys { it.startsWith("Filter") })
        api.describe(listOf("i-1"))
        assertEquals("i-1", form(http.requests.last().third)["InstanceId.1"])
    }

    @Test fun `provider errors carry their code alone and transient ones are retried once`() {
        val http = RecordingHttp { _, _, _ ->
            ok("""<Response><Errors><Error><Code>InvalidInstanceID.NotFound</Code><Message>secret i-1</Message></Error></Errors><RequestID>x</RequestID></Response>""", 400)
        }
        val api = AwsCapacityApi(AwsApi("eu-north-1", { example }, clock, http))
        val missing = assertFailsWith<ProviderError> { api.describe(listOf("i-1")) }
        assertEquals("InvalidInstanceID.NotFound", missing.code)
        assertTrue("secret" !in missing.message)
        http.answer = { _, _, _ -> ok("""<ErrorResponse><Error><Type>Sender</Type><Code>ValidationError</Code><Message>m</Message></Error></ErrorResponse>""", 400) }
        assertEquals("ValidationError", assertFailsWith<ProviderError> { api.replace("i-1") }.code)
        http.answer = { _, _, _ -> ok("<html>bad gateway</html>", 502) }
        assertEquals("HTTP_502", assertFailsWith<ProviderError> { api.replace("i-1") }.code)
        http.answer = { _, _, _ -> ok("""{"__type":"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException","message":"secret"}""", 400) }
        assertEquals("ResourceNotFoundException", assertFailsWith<ProviderError> { api.leases("t").read("general") }.code)
        assertEquals("HTTP_400", ProviderError("not a code: secret", 400).code)
    }

    @Test fun `the real client retries a transient answer once`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var hits = 0
        server.createContext("/") { exchange ->
            hits++
            val body = if (hits == 1) """<ErrorResponse><Error><Code>Throttling</Code></Error></ErrorResponse>""".toByteArray() else "<ok/>".toByteArray()
            exchange.sendResponseHeaders(if (hits == 1) 400 else 200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val throttled = setOf("Throttling")
            val response = ProviderHttp().exchange("POST", URI("http://127.0.0.1:${server.address.port}/"), "x".toByteArray(),
                transient = { it.status >= 500 || AwsApi.xmlErrorCode(it.text) in throttled }) { emptyMap() }
            assertEquals(200, response.status)
            assertEquals(2, hits)
            server.stop(0)
            assertFailsWith<IOException> { ProviderHttp().exchange("GET", URI("http://127.0.0.1:${server.address.port}/")) { emptyMap() } }
        } finally {
            server.stop(0)
        }
    }

    @Test fun `leases, secrets and invocations use the JSON and REST protocols`() {
        val http = RecordingHttp { _, uri, _ ->
            when (uri.host) {
                "dynamodb.eu-north-1.amazonaws.com" -> ok("""{"Item":{"pool":{"S":"general"},"lease_until":{"N":"2800"}}}""")
                "secretsmanager.eu-north-1.amazonaws.com" -> ok("""{"Name":"s","SecretString":"key"}""")
                else -> ProviderResponse(200, mapOf("X-Amz-Function-Error" to listOf("Unhandled")), """{"errorType":"x"}""".toByteArray())
            }
        }
        val api = AwsCapacityApi(AwsApi("eu-north-1", { example }, clock, http))
        assertEquals("2800", api.leases("table").read("general"))
        assertEquals("""{"TableName":"table","Key":{"pool":{"S":"general"}},"ConsistentRead":true}""", http.requests.last().third)
        assertEquals("DynamoDB_20120810.GetItem", http.headers.last()["x-amz-target"])
        assertEquals("application/x-amz-json-1.0", http.headers.last()["content-type"])
        api.leases("table").write("general", 2800)
        assertEquals("""{"TableName":"table","Item":{"pool":{"S":"general"},"lease_until":{"N":"2800"}}}""", http.requests.last().third)
        assertEquals("key", api.secret("arn:secret"))
        assertEquals("""{"SecretId":"arn:secret"}""", http.requests.last().third)
        assertEquals("secretsmanager.GetSecretValue", http.headers.last()["x-amz-target"])
        val (error, output) = api.invoke("lab-renew", "{}".toByteArray(), Duration.ofSeconds(30))
        assertEquals("Unhandled", error)
        assertEquals("""{"errorType":"x"}""", output.toString(Charsets.UTF_8))
        assertEquals("https://lambda.eu-north-1.amazonaws.com/2015-03-31/functions/lab-renew/invocations", http.requests.last().second.toString())
        assertEquals("RequestResponse", http.headers.last()["x-amz-invocation-type"])
        http.answer = { _, _, _ -> ProviderResponse(429, mapOf("x-amzn-ErrorType" to listOf("TooManyRequestsException:http://internal")), "{}".toByteArray()) }
        assertEquals("TooManyRequestsException", assertFailsWith<ProviderError> { api.invoke("lab-renew", "{}".toByteArray(), Duration.ofSeconds(30)) }.code)
    }

    @Test fun `a profile's role is assumed with its source profile's key`() {
        val home = Files.createTempDirectory("aws-home")
        try {
            Files.createDirectories(home.resolve(".aws"))
            Files.writeString(home.resolve(".aws/credentials"), "[base]\naws_access_key_id = AKIDBASE\naws_secret_access_key = base-secret\n")
            Files.writeString(home.resolve(".aws/config"), """
                [profile adapter]
                role_arn = arn:aws:iam::1:role/lab-adapter
                source_profile = base
                region = eu-north-1
                [profile self]
                role_arn = arn:aws:iam::1:role/self
                source_profile = self
                aws_access_key_id = AKIDSELF
                aws_secret_access_key = self-secret
                [profile mfa]
                role_arn = arn:aws:iam::1:role/mfa
                source_profile = base
                mfa_serial = arn:aws:iam::1:mfa/me
                """.trimIndent())
            val asked = mutableListOf<Pair<AwsCredentials, AwsProfileCredentials.AssumeRole>>()
            val expires = Instant.now().plusSeconds(3600)
            fun profile(name: String) = AwsProfileCredentials(name, mapOf("HOME" to home.toString()), region = "eu-north-1") { source, role ->
                asked += source to role
                AwsCredentials("ASIAROLE", "role-secret", "session", expires)
            }
            val adapter = profile("adapter")
            assertEquals(AwsCredentials("ASIAROLE", "role-secret", "session", expires), adapter())
            adapter()
            assertEquals(1, asked.size, "assumed-role credentials are reused until they near expiry")
            val (source, role) = asked.single()
            assertEquals(AwsCredentials("AKIDBASE", "base-secret"), source)
            assertEquals("arn:aws:iam::1:role/lab-adapter", role.roleArn)
            assertEquals("eu-north-1", role.region)
            profile("self")()
            assertEquals(AwsCredentials("AKIDSELF", "self-secret"), asked.last().first)
            assertFailsWith<IllegalStateException> { profile("mfa")() }
            // Credentials shorter-lived than the refresh margin, such as an `aws login` session's, are reused until halfway.
            var now = Instant.parse("2026-11-09T12:00:00Z")
            val clock = object : Clock() {
                override fun getZone() = ZoneOffset.UTC
                override fun withZone(zone: java.time.ZoneId?) = this
                override fun instant() = now
            }
            var fetched = 0
            val short = AwsProfileCredentials("adapter", mapOf("HOME" to home.toString()), clock, "eu-north-1") { _, _ ->
                fetched++; AwsCredentials("ASIASHORT", "s", "t", now.plusSeconds(900))
            }
            short(); short()
            now = now.plusSeconds(449); short()
            assertEquals(1, fetched)
            now = now.plusSeconds(2); short()
            assertEquals(2, fetched)
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test fun `STS AssumeRole is a query action at the regional endpoint`() {
        val http = RecordingHttp { _, _, _ ->
            ok("""<AssumeRoleResponse><AssumeRoleResult><Credentials><AccessKeyId>ASIA</AccessKeyId><SecretAccessKey>s2</SecretAccessKey>
                <SessionToken>t</SessionToken><Expiration>2099-01-01T00:00:00Z</Expiration></Credentials></AssumeRoleResult></AssumeRoleResponse>""")
        }
        val root = AwsApi("eu-north-1", { AwsCredentials("AKIDBASE", "s") }, clock, http)
            .query("sts", "AssumeRole", "2011-06-15", mapOf("RoleArn" to "arn:aws:iam::1:role/r", "RoleSessionName" to "eignex-lab-1"))
        assertEquals("ASIA", root.getElementsByTagName("AccessKeyId").item(0).textContent)
        assertEquals("https://sts.eu-north-1.amazonaws.com/", http.requests.single().second.toString())
    }
}
