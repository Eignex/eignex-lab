package com.eignex.lab.util

import kotlinx.serialization.Serializable
import com.eignex.lab.domain.SHA256

/** A full git commit id: 40 lowercase hex digits. A ref (`main`, a tag) is not one; resolve it first. */
@Serializable
@JvmInline
value class CommitSha(val hex: String) {
    init { require(isValid(hex)) { "not a full commit id: $hex" } }

    override fun toString() = hex

    companion object {
        private val PATTERN = Regex("[0-9a-f]{40}")

        fun isValid(text: String): Boolean = PATTERN.matches(text)
    }
}

/** A SHA-256 digest: 64 lowercase hex digits. */
@Serializable
@JvmInline
value class Sha256(val hex: String) {
    init { require(isValid(hex)) { "not a SHA-256: $hex" } }

    override fun toString() = hex

    companion object {
        fun isValid(text: String): Boolean = SHA256.matches(text)
    }
}
