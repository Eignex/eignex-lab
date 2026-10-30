package com.eignex.lab.nomad

import com.eignex.lab.domain.*
import com.eignex.lab.store.Store
import kotlinx.serialization.json.*

/** Preparation waits for a node's compiler capacity before claiming a charged attempt. */
internal class PreparationAdmission(private val store: Store) {
    internal data class Reservation(val node: String?, val nodeClass: String?, val native: Boolean)

    private fun reservations() = store.execution.activePreparations().map { session ->
        val placement = store.execution.placement(session.id)
        Reservation(session.node, placement.nodeClass, placement.native)
    }

    fun allows(nodeClass: String?, native: Boolean, limit: Int) = allows(nodeClass, native, limit, reservations())
    fun place(job: JsonObject): JsonObject = place(job, reservations())

    companion object {
        const val LIMIT = "lab_preparation_slots"
        private const val EXCLUDED = "lab_preparation_excluded"
        private const val NODE = "\${node.unique.id}"

        fun allows(nodeClass: String?, native: Boolean, limit: Int, held: List<Reservation>): Boolean {
            require(limit in 1..64)
            val matching = held.filter { it.nodeClass == nodeClass && it.native == native }
            // Serialize unbound cloud preparations so the next placement sees the preceding reservation.
            return if (native) matching.size < limit else matching.none { it.node == null }
        }

        fun mark(job: JsonObject, limit: Int): JsonObject {
            require(limit in 1..64)
            return JsonObject(job + ("Meta" to JsonObject(job.getValue("Meta").jsonObject + (LIMIT to JsonPrimitive(limit.toString())))))
        }

        fun clear(job: JsonObject): JsonObject {
            val meta = job["Meta"]?.jsonObject ?: return job
            val encoded = meta[EXCLUDED]?.jsonPrimitive?.content ?: return job
            val excluded = Json.parseToJsonElement(encoded).jsonArray.map { it.jsonPrimitive.content }.toSet()
            val constraints = job.getValue("Constraints").jsonArray.filterNot { value ->
                val constraint = value.jsonObject
                constraint["LTarget"]?.jsonPrimitive?.content == NODE && constraint["Operand"]?.jsonPrimitive?.content == "!=" &&
                    constraint["RTarget"]?.jsonPrimitive?.content in excluded
            }
            return JsonObject(job + mapOf("Meta" to JsonObject(meta - EXCLUDED), "Constraints" to JsonArray(constraints)))
        }

        fun place(job: JsonObject, held: List<Reservation>): JsonObject {
            val base = clear(job)
            val meta = base["Meta"]?.jsonObject ?: return base
            val limit = meta[LIMIT]?.jsonPrimitive?.intOrNull ?: return base
            require(limit in 1..64)
            val placement = SessionPlacement.of(base)
            val cloud = placement.pool as? WorkerPool.Cloud ?: return base
            val full = held.filter { !it.native && it.node != null && it.nodeClass == placement.nodeClass }
                .groupingBy { requireNotNull(it.node) }.eachCount().filterValues { it >= limit }.keys
            val excluded = (full - cloud.excludedNodes).sorted()
            if (excluded.isEmpty()) return base
            val constraints = base.getValue("Constraints").jsonArray + excluded.map { node -> buildJsonObject {
                put("LTarget", NODE); put("Operand", "!="); put("RTarget", node)
            } }
            return JsonObject(base + mapOf("Meta" to JsonObject(meta + (EXCLUDED to JsonPrimitive(JsonArray(excluded.map(::JsonPrimitive)).toString()))),
                "Constraints" to JsonArray(constraints)))
        }
    }
}
