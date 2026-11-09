package com.eignex.lab.checker

import com.eignex.lab.domain.*
import com.eignex.lab.reference.*

/** Historical checker custody identities stay valid after the independent reference images were renamed. Any of
 *  [policies] may name the importer. */
fun sourcePreservingXcspIdentity(vararg policies: String): Regex =
    Regex("(?:klause|eignex-lab)-xcsp3-cpsat:[^@|]+@sha256:[0-9a-f]{64}\\|(?:${policies.joinToString("|") { Regex.escape(it) }})")
