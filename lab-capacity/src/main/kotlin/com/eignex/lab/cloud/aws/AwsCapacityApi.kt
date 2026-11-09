package com.eignex.lab.cloud.aws

import com.eignex.lab.cloud.ProviderError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.Instant

/** An Auto Scaling group member: [protectedFromScaleIn] is null when the provider did not say. */
data class AsgMember(val id: String, val lifecycleState: String, val protectedFromScaleIn: Boolean? = null)

data class AsgGroup(val minSize: Int, val maxSize: Int, val desiredCapacity: Int, val instances: List<AsgMember>, val tags: Map<String, String>)

/** A scaling activity's status code and message, as DescribeScalingActivities lists them. */
data class AsgActivity(val statusCode: String, val statusMessage: String?)

/** An EC2 instance as DescribeInstances describes it, its launch time in epoch milliseconds. */
data class Ec2Instance(val id: String, val launchedAt: Long, val state: String, val tags: Map<String, String>)

/** The Auto Scaling calls capacity control makes. */
interface AutoScaling {
    /** The group named [name], or null when there is none. */
    fun group(name: String): AsgGroup?
    /** The group's latest scaling activity, or null when it has none. */
    fun latestActivity(name: String): AsgActivity?
    fun update(name: String, minSize: Int? = null, desiredCapacity: Int? = null, maxSize: Int? = null)
    fun unprotect(name: String, ids: List<String>)
    /** Terminate member [id] without changing the group's desired capacity, so the group replaces it. */
    fun replace(id: String)
}

/** The EC2 calls capacity control makes. */
interface Ec2 {
    /** The instances [ids] names; [ProviderError] `InvalidInstanceID.NotFound` when one does not exist. */
    fun describe(ids: List<String>): List<Ec2Instance>
    /** The instances whose ids are among [ids], omitting vanished ones. */
    fun describeAmong(ids: List<String>): List<Ec2Instance>
    /** Every instance with the given tags in one of [states]. */
    fun tagged(tags: Map<String, String>, states: List<String>): List<Ec2Instance>
    fun terminate(ids: List<String>)
}

/** A pool's lease row: its expiry in epoch seconds, as written, or null when there is none. */
interface LeaseTable {
    fun read(pool: String): String?
    fun write(pool: String, until: Long)
}

/** The Auto Scaling, EC2, DynamoDB, Secrets Manager and Lambda calls, over [api]. */
class AwsCapacityApi(private val api: AwsApi) : AutoScaling, Ec2 {
    override fun group(name: String): AsgGroup? {
        val result = api.query("autoscaling", "DescribeAutoScalingGroups", ASG_VERSION, mapOf("AutoScalingGroupNames.member.1" to name))
        val group = result.child("DescribeAutoScalingGroupsResult")?.members("AutoScalingGroups")?.firstOrNull() ?: return null
        return AsgGroup(
            minSize = group.text("MinSize")!!.toInt(), maxSize = group.text("MaxSize")!!.toInt(),
            desiredCapacity = group.text("DesiredCapacity")!!.toInt(),
            instances = group.members("Instances").map { member ->
                AsgMember(member.text("InstanceId")!!, member.text("LifecycleState")!!, member.text("ProtectedFromScaleIn")?.toBooleanStrictOrNull())
            },
            tags = group.members("Tags").associate { it.text("Key")!! to (it.text("Value") ?: "") },
        )
    }

    override fun latestActivity(name: String): AsgActivity? {
        val result = api.query("autoscaling", "DescribeScalingActivities", ASG_VERSION, mapOf("AutoScalingGroupName" to name, "MaxRecords" to "1"))
        val activity = result.child("DescribeScalingActivitiesResult")?.members("Activities")?.firstOrNull() ?: return null
        return AsgActivity(activity.text("StatusCode")!!, activity.text("StatusMessage"))
    }

    override fun update(name: String, minSize: Int?, desiredCapacity: Int?, maxSize: Int?) {
        val parameters = linkedMapOf("AutoScalingGroupName" to name)
        minSize?.let { parameters["MinSize"] = it.toString() }
        desiredCapacity?.let { parameters["DesiredCapacity"] = it.toString() }
        maxSize?.let { parameters["MaxSize"] = it.toString() }
        api.query("autoscaling", "UpdateAutoScalingGroup", ASG_VERSION, parameters)
    }

    override fun unprotect(name: String, ids: List<String>) {
        val parameters = linkedMapOf("AutoScalingGroupName" to name, "ProtectedFromScaleIn" to "false")
        ids.forEachIndexed { index, id -> parameters["InstanceIds.member.${index + 1}"] = id }
        api.query("autoscaling", "SetInstanceProtection", ASG_VERSION, parameters)
    }

    override fun replace(id: String) {
        api.query("autoscaling", "TerminateInstanceInAutoScalingGroup", ASG_VERSION,
            mapOf("InstanceId" to id, "ShouldDecrementDesiredCapacity" to "false"))
    }

    override fun describe(ids: List<String>): List<Ec2Instance> =
        pages(ids.withIndex().associate { (index, id) -> "InstanceId.${index + 1}" to id })

    override fun describeAmong(ids: List<String>): List<Ec2Instance> =
        pages(mapOf("Filter.1.Name" to "instance-id") + ids.withIndex().associate { (index, id) -> "Filter.1.Value.${index + 1}" to id })

    override fun tagged(tags: Map<String, String>, states: List<String>): List<Ec2Instance> {
        val parameters = linkedMapOf<String, String>()
        var filter = 0
        tags.forEach { (key, value) -> filter++; parameters["Filter.$filter.Name"] = "tag:$key"; parameters["Filter.$filter.Value.1"] = value }
        filter++
        parameters["Filter.$filter.Name"] = "instance-state-name"
        states.forEachIndexed { index, state -> parameters["Filter.$filter.Value.${index + 1}"] = state }
        return pages(parameters)
    }

    override fun terminate(ids: List<String>) {
        api.query("ec2", "TerminateInstances", EC2_VERSION, ids.withIndex().associate { (index, id) -> "InstanceId.${index + 1}" to id })
    }

    private fun pages(parameters: Map<String, String>): List<Ec2Instance> {
        val instances = mutableListOf<Ec2Instance>()
        var token: String? = null
        do {
            val response = api.query("ec2", "DescribeInstances", EC2_VERSION, parameters + listOfNotNull(token?.let { "NextToken" to it }))
            for (reservation in response.members("reservationSet")) {
                for (instance in reservation.members("instancesSet")) {
                    instances += Ec2Instance(instance.text("instanceId")!!, Instant.parse(instance.text("launchTime")!!).toEpochMilli(),
                        instance.child("instanceState")?.text("name") ?: "", instance.members("tagSet").associate { it.text("key")!! to (it.text("value") ?: "") })
                }
            }
            token = response.text("nextToken")?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return instances
    }

    /** The lease rows of DynamoDB [table], each keyed by its pool. */
    fun leases(table: String) = object : LeaseTable {
        override fun read(pool: String): String? {
            val item = api.json("dynamodb", "DynamoDB_20120810.GetItem", DYNAMODB_JSON, buildJsonObject {
                put("TableName", table)
                putJsonObject("Key") { putJsonObject("pool") { put("S", pool) } }
                put("ConsistentRead", true)
            })["Item"] as? JsonObject
            return ((item?.get("lease_until") as? JsonObject)?.get("N") as? JsonPrimitive)?.content
        }

        override fun write(pool: String, until: Long) {
            api.json("dynamodb", "DynamoDB_20120810.PutItem", DYNAMODB_JSON, buildJsonObject {
                put("TableName", table)
                putJsonObject("Item") {
                    putJsonObject("pool") { put("S", pool) }
                    putJsonObject("lease_until") { put("N", until.toString()) }
                }
            })
        }
    }

    /** The string value of secret [id]. */
    fun secret(id: String): String =
        api.json("secretsmanager", "secretsmanager.GetSecretValue", "application/x-amz-json-1.1", buildJsonObject { put("SecretId", id) })["SecretString"]
            .string() ?: throw ProviderError("SecretStringMissing", 200)

    /** Lambda [function]'s synchronous answer to [payload]: its error type when it failed, and its output. */
    fun invoke(function: String, payload: ByteArray, timeout: Duration) = api.invoke(function, payload, timeout)

    companion object {
        const val ASG_VERSION = "2011-01-01"
        const val EC2_VERSION = "2016-11-15"
        const val DYNAMODB_JSON = "application/x-amz-json-1.0"
    }
}
