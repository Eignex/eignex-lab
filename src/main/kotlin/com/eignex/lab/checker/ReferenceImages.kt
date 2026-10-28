package com.eignex.lab.checker

/** Historical checker custody identities stay valid after the independent reference images were renamed. */
internal fun sourcePreservingXcspIdentity(policy: String): Regex =
    Regex("(?:klause|eignex-lab)-xcsp3-cpsat:[^@|]+@sha256:[0-9a-f]{64}\\|${Regex.escape(policy)}")
