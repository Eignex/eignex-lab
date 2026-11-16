package com.eignex.lab.util

import kotlinx.serialization.json.Json

/** Reads JSON that a newer or older version may have written: keys it does not know are skipped. */
internal val lenientJson = Json { ignoreUnknownKeys = true }

/** Writes every property, defaults included, so a reader need not share this version's defaults. */
internal val defaultsJson = Json { encodeDefaults = true }
