package com.eignex.lab

import kotlinx.coroutines.Dispatchers

/** Separate elastic IO views keep uploads, fsync and file hashing from occupying heartbeat/capacity workers. */
internal object CoordinatorIo {
    val control = Dispatchers.IO.limitedParallelism(8)
    val artifacts = Dispatchers.IO.limitedParallelism(4)
    val capacity = Dispatchers.IO.limitedParallelism(2)
}
