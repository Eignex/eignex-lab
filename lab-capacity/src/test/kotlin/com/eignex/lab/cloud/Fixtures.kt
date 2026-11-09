package com.eignex.lab.cloud

import com.eignex.lab.cloud.aws.AsgActivity
import com.eignex.lab.cloud.aws.AsgGroup
import com.eignex.lab.cloud.aws.AsgMember
import com.eignex.lab.cloud.aws.AutoScaling
import com.eignex.lab.cloud.aws.Ec2
import com.eignex.lab.cloud.aws.Ec2Instance
import com.eignex.lab.cloud.aws.LeaseTable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A grant of [fields] (the startup or ordinary payload's fields, in order) signed with [key]. */
fun grant(key: String, fields: JsonObject): JsonObject = CapacityGrants.sign(fields.toString().toByteArray(), key)

fun payload(pool: String = "general", maxNodes: Int = 4, desired: Int = 2, issuedAt: Long = 1_000_000, schemaVersion: Int = 1,
            extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) = JsonObject(buildJsonObject {
    put("schemaVersion", schemaVersion); put("pool", pool); put("maxNodes", maxNodes); put("desired", desired)
    put("issuedAt", issuedAt); put("nonce", "test")
} + extra)

/** Every provider call the fakes saw, in order: `asg.update`, `ec2.terminate`, `leases.write` and so on. */
class Calls : ArrayList<String>() {
    fun index(call: String) = indexOf(call).also { check(it >= 0) { "$call never happened: $this" } }
}

class FakeAsg(val calls: Calls) : AutoScaling {
    var group: AsgGroup? = AsgGroup(0, 4, 2, listOf(AsgMember("i-1", "InService", true)), mapOf("lab:deployment" to "lab", "lab:pool" to "general"))
    var activity: AsgActivity? = null
    val updates = mutableListOf<Triple<Int?, Int?, Int?>>()
    val unprotected = mutableListOf<List<String>>()
    val replaced = mutableListOf<String>()
    var onGroup: () -> Unit = {}
    var onReplace: (String) -> Unit = {}

    override fun group(name: String): AsgGroup? { calls += "asg.group"; onGroup(); return group }
    override fun latestActivity(name: String): AsgActivity? { calls += "asg.activity"; return activity }
    override fun update(name: String, minSize: Int?, desiredCapacity: Int?, maxSize: Int?) {
        calls += "asg.update"; updates += Triple(minSize, desiredCapacity, maxSize)
    }
    override fun unprotect(name: String, ids: List<String>) { calls += "asg.unprotect"; unprotected += ids }
    override fun replace(id: String) { calls += "asg.replace"; onReplace(id); replaced += id }
}

class FakeEc2(val calls: Calls) : Ec2 {
    var instances: List<Ec2Instance> = listOf(Ec2Instance("i-1", 1000, "running", mapOf("lab:deployment" to "lab", "lab:pool" to "general")))
    var onDescribe: (List<String>) -> List<Ec2Instance> = { instances }
    val described = mutableListOf<List<String>>()
    val among = mutableListOf<List<String>>()
    val taggedQueries = mutableListOf<Pair<Map<String, String>, List<String>>>()
    val terminated = mutableListOf<List<String>>()

    override fun describe(ids: List<String>): List<Ec2Instance> { calls += "ec2.describe"; described += ids; return onDescribe(ids) }
    override fun describeAmong(ids: List<String>): List<Ec2Instance> { calls += "ec2.describeAmong"; among += ids; return instances.filter { it.id in ids } }
    override fun tagged(tags: Map<String, String>, states: List<String>): List<Ec2Instance> {
        calls += "ec2.tagged"; taggedQueries += tags to states; return instances
    }
    override fun terminate(ids: List<String>) { calls += "ec2.terminate"; terminated += ids }
}

class FakeLeases(val calls: Calls) : LeaseTable {
    var row: String? = null
    val written = mutableListOf<Long>()
    override fun read(pool: String): String? { calls += "leases.read"; return row }
    override fun write(pool: String, until: Long) { calls += "leases.write"; written += until; row = until.toString() }
}

fun JsonObject.long(name: String) = (getValue(name) as JsonPrimitive).content.toLong()
