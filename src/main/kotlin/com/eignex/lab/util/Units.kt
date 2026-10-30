package com.eignex.lab.util

/*
 * The units the lab's settings and reports count in. Disk space is decimal, as disks and `df -H` count it: a GB is
 * 10^9 bytes and an MB 10^6. Memory, as Nomad and the JVM count it, is binary and named so: MiB.
 */

const val BYTES_PER_GB = 1_000_000_000L
const val BYTES_PER_MB = 1_000_000L
const val BYTES_PER_MIB = 1024L * 1024

const val MS_PER_SEC = 1000L
const val MS_PER_HOUR = 3_600_000L
const val MS_PER_DAY = 24 * MS_PER_HOUR
