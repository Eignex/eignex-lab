package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/* How execution rows store what every session or preparation repeats: kept once in `manifest_parts`, named by `<key>PartRef`. */

internal val MANIFEST_PARTS = setOf("cases", "bundles", "sharedInputs")
internal const val MANIFEST_PART_REF = "PartRef"
internal val PREPARATION_PARTS = setOf("bundles")

/** [manifest] as it is stored: its [MANIFEST_PARTS], which every session of a job repeats, kept once each in
 *  `manifest_parts` by their sha256 and named as `<key>PartRef` in their place. */
internal fun Tx.splitManifest(manifest: String): String {
    val parsed = runCatching { Json.parseToJsonElement(manifest) }.getOrNull() as? JsonObject ?: return manifest
    if (MANIFEST_PARTS.none { parsed[it] is JsonArray || parsed[it] is JsonObject }) return manifest
    return Json.encodeToString(JsonElement.serializer(), splitParts(parsed, MANIFEST_PARTS))
}

/** [obj] with its [keys] kept once each in `manifest_parts` by their sha256, named as `<key>PartRef` in their place. */
internal fun Tx.splitParts(obj: JsonObject, keys: Set<String>): JsonObject {
    val stored = LinkedHashMap<String, JsonElement>(obj.size)
    for ((key, value) in obj) {
        if (key in keys && (value is JsonArray || value is JsonObject)) {
            val text = Json.encodeToString(JsonElement.serializer(), value); val sha = ExecutionStore.digest(text)
            sqlUpdate("INSERT OR IGNORE INTO manifest_parts (sha, part) VALUES (?, ?)", sha, text)
            stored[key + MANIFEST_PART_REF] = JsonPrimitive(sha)
        } else stored[key] = value
    }
    return JsonObject(stored)
}

/** [obj] as it was written: each `<key>PartRef` back to the part it names, in its place; a part no longer stored is
 *  [missing]'s value for its key, by default an error. */
internal fun Tx.joinParts(obj: JsonObject, missing: (key: String, sha: String) -> JsonElement = { _, sha -> error("manifest part $sha is missing") }): JsonObject {
    val joined = LinkedHashMap<String, JsonElement>(obj.size)
    for ((key, value) in obj) {
        if (!key.endsWith(MANIFEST_PART_REF)) { joined[key] = value; continue }
        val sha = (value as JsonPrimitive).content
        val name = key.removeSuffix(MANIFEST_PART_REF)
        val part = sqlRows("SELECT part FROM manifest_parts WHERE sha = ?", sha) { it.getString(1) }.singleOrNull()
        joined[name] = part?.let(Json::parseToJsonElement) ?: missing(name, sha)
    }
    return JsonObject(joined)
}

/** [manifest] as it was written: each `<key>PartRef` back to the part it names, in its place. */
internal fun Tx.joinManifest(manifest: String): String {
    if (MANIFEST_PART_REF !in manifest) return manifest
    val parsed = Json.parseToJsonElement(manifest) as? JsonObject ?: return manifest
    return Json.encodeToString(JsonElement.serializer(), joinParts(parsed))
}

/** A committed result as it is stored: its preparation's bundles, the file inventories every session manifest of the
 *  job repeats (about a megabyte), kept once in `manifest_parts`. */
internal fun Tx.splitResult(result: String): String {
    val parsed = runCatching { Json.parseToJsonElement(result) }.getOrNull() as? JsonObject ?: return result
    val preparation = parsed["preparation"] as? JsonObject ?: return result
    if (PREPARATION_PARTS.none { preparation[it] is JsonArray || preparation[it] is JsonObject }) return result
    return Json.encodeToString(JsonElement.serializer(), JsonObject(parsed + ("preparation" to splitParts(preparation, PREPARATION_PARTS))))
}

/**
 * A stored result as it was committed: its preparation's parts back in place. Releases 85fa1892 to the fix of
 * [ExecutionMaintenance.dropUnusedManifestParts] dropped bundle parts of done jobs' preparations: such a preparation
 * reads with no bundles, so its selections, plans and artifacts stay readable.
 */
internal fun Tx.joinResult(result: String): String {
    if (MANIFEST_PART_REF !in result) return result
    val parsed = Json.parseToJsonElement(result) as? JsonObject ?: return result
    val preparation = parsed["preparation"] as? JsonObject ?: return result
    return Json.encodeToString(JsonElement.serializer(), JsonObject(parsed + ("preparation" to joinParts(preparation) { _, _ -> JsonArray(emptyList()) })))
}
