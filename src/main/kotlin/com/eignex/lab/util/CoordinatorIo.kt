package com.eignex.lab.util

import kotlinx.coroutines.Dispatchers

/** Separate elastic IO views keep uploads, fsync and file hashing from occupying heartbeat/capacity workers. */
internal object CoordinatorIo {
    val control = Dispatchers.IO.limitedParallelism(8)
    val artifacts = Dispatchers.IO.limitedParallelism(4)
    // Bounded small commits must not wait behind multi-megabyte input streams.
    val results = Dispatchers.IO.limitedParallelism(4)
    val packedResults = Dispatchers.IO.limitedParallelism(2)
    val capacity = Dispatchers.IO.limitedParallelism(2)
}
