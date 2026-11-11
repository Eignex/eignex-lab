package com.eignex.lab.cloud.aws

import com.eignex.lab.cloud.CapacityGrants
import com.eignex.lab.cloud.CapacityRejected
import com.eignex.lab.cloud.ProviderError
import com.eignex.lab.domain.CapacityWire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * One AWS capacity pool: its Auto Scaling [group], owned by [deployment] and [pool] through the group's and its
 * instances' `lab:deployment` and `lab:pool` tags, whose lease row lives in [leases] and whose finite size is [maxNodes].
 * Workers cannot invoke these; the renewal function opens the pool only for a fresh coordinator grant, and the
 * independent expiry function closes it when renewals stop.
 */
class AwsPool(val group: String, val deployment: String, val pool: String, val maxNodes: Int,
              val asg: AutoScaling, val ec2: Ec2?, val leases: LeaseTable, val clock: () -> Long = System::currentTimeMillis) {

    /** The pool's group, or null when there is none; [CapacityRejected] when it is not this pool's. */
    fun owned(): AsgGroup? {
        val value = asg.group(group) ?: return null
        if (value.tags["lab:deployment"] != deployment || value.tags["lab:pool"] != pool) throw CapacityRejected("capacity group ownership mismatch")
        return value
    }

    private fun member(id: String) = owned()?.instances?.firstOrNull { it.id == id }
    private fun gone(member: AsgMember?) = member == null || member.lifecycleState.startsWith("Terminating")
    private fun checkReplaceable(member: AsgMember) {
        if (member.protectedFromScaleIn != false || member.lifecycleState !in setOf("Pending", "InService")) {
            throw CapacityRejected("startup retirement member is protected or not replaceable")
        }
    }

    private fun verify(grant: JsonElement?, key: String) = CapacityGrants.verify(grant, key, pool, maxNodes, clock())

    /**
     * Renew the pool's lease for [grant], a fresh grant signed with [key]: persist the lease, then reopen a closed group
     * to its finite limit, then replace any startup retirement the grant authorizes. Each step rechecks the grant, so a
     * grant that ages out during provider calls stops before the next one.
     */
    fun renew(grant: JsonElement?, key: String): JsonObject {
        verify(grant, key)
        val current = owned()
        if (current == null || current.maxSize !in setOf(0, maxNodes) || current.minSize != 0) {
            throw CapacityRejected("capacity group finite limit differs from configuration")
        }
        verify(grant, key)
        val until = clock() / 1000 + CapacityGrants.LEASE_SECONDS
        leases.write(pool, until)
        // Persist expiry before opening capacity. A crash here leaves a closed group, never an unleased open one.
        verify(grant, key)
        if (current.maxSize == 0) asg.update(group, minSize = 0, maxSize = maxNodes)
        val value = verify(grant, key)
        // Nomad's scaler cannot shrink a failed launch that never registered a node. Cancel only a completely
        // empty group's unfilled target, under fresh signed zero demand; never remove a real member here.
        if (CapacityGrants.integer(value["desired"]) == 0L && current.desiredCapacity > 0) {
            val latest = owned()
            if (latest != null && latest.instances.isEmpty() && latest.desiredCapacity > 0) {
                verify(grant, key)
                asg.update(group, desiredCapacity = 0)
            }
        }
        val retired = retireStartups(value, grant, key)
        return buildJsonObject {
            put("leaseUntil", until)
            if (retired.isNotEmpty()) putJsonArray("startupRetired") { retired.forEach { add(it) } }
        }
    }

    /**
     * Replace each instance [value] (verified [grant]) retires: an unprotected, pending or running member of this group,
     * tagged as this pool's, launched exactly when the coordinator saw it and past its registration grace. The group
     * replaces it without changing its desired size. A member already gone or terminating counts as done.
     */
    private fun retireStartups(value: JsonObject, grant: JsonElement?, key: String): List<String> {
        val retired = mutableListOf<String>()
        for ((id, launchedAt) in CapacityGrants.startupRetirements(value)) {
            if (ec2 == null) throw CapacityRejected("startup recovery requires provider launch identity")
            val member = member(id)
            if (gone(member)) continue  // An earlier invocation or ordinary Spot loss already removed this exact member.
            checkReplaceable(member!!)
            val instances = try {
                ec2.describe(listOf(id))
            } catch (error: ProviderError) {
                if (error.code != "InvalidInstanceID.NotFound") throw error
                if (!gone(member(id))) throw error
                continue
            }
            val instance = instances.singleOrNull() ?: throw CapacityRejected("startup retirement instance missing")
            if (instance.id != id || instance.launchedAt != launchedAt || instance.tags["lab:deployment"] != deployment ||
                instance.tags["lab:pool"] != pool || instance.state !in setOf("pending", "running")) {
                throw CapacityRejected("startup retirement ownership or launch identity mismatch")
            }
            if (clock() - instance.launchedAt < CapacityWire.STARTUP_GRACE_MS) throw CapacityRejected("startup registration grace has not expired")
            val latest = member(id)
            if (gone(latest)) continue
            checkReplaceable(latest!!)
            // Revalidate the bounded authorization after provider reads, immediately before normal ASG replacement.
            verify(grant, key)
            try {
                asg.replace(id)
            } catch (error: ProviderError) {
                if (error.code != "ValidationError") throw error
                if (!gone(member(id))) throw error
            }
            retired += id
        }
        return retired
    }

    /**
     * Close the pool unless its lease is current: unprotect its members, clamp min, desired and max to zero, then
     * terminate every remaining instance tagged as this pool's. A lease further ahead than one renewal can set is
     * treated as expired.
     */
    fun expire(): JsonObject {
        val at = clock() / 1000
        val until = leases.read(pool)?.trim()?.toLongOrNull() ?: 0
        if (at < until && until <= at + CapacityGrants.LEASE_SECONDS) return buildJsonObject { put("expired", false) }
        val current = owned()
        if (current != null) {
            current.instances.filter { it.protectedFromScaleIn == true }.map { it.id }.chunked(50).forEach { asg.unprotect(group, it) }
            // Killing instances alone would cause replacements. Clamp the complete capacity state first.
            asg.update(group, minSize = 0, desiredCapacity = 0, maxSize = 0)
        }
        val ids = requireNotNull(ec2) { "expiry requires EC2" }
            .tagged(linkedMapOf("lab:deployment" to deployment, "lab:pool" to pool), listOf("pending", "running", "stopping", "stopped"))
            .map { it.id }
        ids.chunked(100).forEach { ec2.terminate(it) }
        return buildJsonObject { put("expired", true); put("terminated", ids.size) }
    }
}
