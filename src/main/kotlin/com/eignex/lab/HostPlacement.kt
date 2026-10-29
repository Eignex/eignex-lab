package com.eignex.lab

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Where a job may run, as [Placement] decided it: the local machine only, a cloud node only, or whichever is free
 * first. Stored as [stored] (`lab`, `aws`, `auto`), the names old jobs and clients use; [word] is what pages and
 * messages call it, and is accepted wherever a host is read.
 */
@Serializable(with = HostPlacementSerializer::class)
enum class HostPlacement(val stored: String, val word: String) {
    LOCAL("lab", "local"),
    CLOUD("aws", "cloud"),
    AUTO("auto", "auto");

    override fun toString() = stored

    companion object {
        fun of(text: String): HostPlacement = entries.firstOrNull { it.stored == text || it.word == text }
            ?: throw IllegalArgumentException("unknown host $text: use local, cloud or auto")
    }
}

/** Reads a stored or worded host, writes the stored one. */
object HostPlacementSerializer : KSerializer<HostPlacement> {
    override val descriptor = PrimitiveSerialDescriptor("com.eignex.lab.HostPlacement", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: HostPlacement) = encoder.encodeString(value.stored)
    override fun deserialize(decoder: Decoder): HostPlacement = HostPlacement.of(decoder.decodeString())
}
