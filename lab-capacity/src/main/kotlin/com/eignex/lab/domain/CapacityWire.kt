package com.eignex.lab.domain

/**
 * The wire format the coordinator's capacity endpoints share with the cloud functions that verify its grants
 * ([com.eignex.lab.cloud.CapacityGrants]), the autoscaler and the node bootstrap: `deploy/capacity-wire.json` holds the
 * same values, and tests hold both to it.
 */
object CapacityWire {
    /** What a grant's HMAC is computed over, before its payload. */
    const val GRANT_DOMAIN = "eignex-capacity-v1\n"
    const val GRANT_SCHEMA_VERSION = 1
    const val STARTUP_GRANT_SCHEMA_VERSION = 2
    const val STARTUP_GRACE_MS = 900_000L
    /** How long a grant, and the sample it was issued from, stays fresh. */
    const val FRESH_MS = 30_000L
    /** How far ahead of the verifier's clock a grant may be issued. */
    const val CLOCK_SKEW_MS = 5_000L
    /** The metric the autoscaler asks each pool's desired size by. */
    const val DESIRED_METRIC = "lab_capacity_desired_nodes"
    /** The Nomad node meta a cloud node names its provider instance by. */
    const val CAPACITY_ID_META = "lab_capacity_id"
}
