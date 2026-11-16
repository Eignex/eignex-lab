package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * What each capacity pool is, as everything outside the lab that runs one must agree: the autoscaler's targets, the
 * provider adapters and their Terraform modules. `eignex-lab capacity-export CAPACITY.json` writes it from the lab's
 * own capacity settings ([CapacitySettings]), so a pool's id, node class, size and limit are written down once, here,
 * and each consumer checks itself against the export instead of carrying a copy.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CapacityContract(@EncodeDefault val schemaVersion: Int = SCHEMA_VERSION, val pools: List<Pool>) {
    @Serializable
    data class Pool(val id: String, val nodeClass: String, val maxNodes: Int, val vcpus: Int, val memoryMiB: Int, val market: CapacityMarket)

    companion object {
        const val SCHEMA_VERSION = 1

        fun of(settings: CapacitySettings) = CapacityContract(pools = settings.pools.map {
            Pool(it.id, it.nodeClass, it.maxNodes, it.vcpus, it.memoryMiB, it.market)
        })

        private val json = Json { prettyPrint = true }

        /** `capacity-export CAPACITY.json`: the contract of the capacity settings file, as JSON on standard output. */
        fun main(args: List<String>): Int {
            if (args.size != 1) {
                System.err.println("usage: eignex-lab capacity-export CAPACITY.json")
                return 2
            }
            println(json.encodeToString(serializer(), of(CapacitySettings.read(Path.of(args.single())))))
            return 0
        }
    }
}
