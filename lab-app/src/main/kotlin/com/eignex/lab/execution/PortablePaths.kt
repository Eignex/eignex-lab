package com.eignex.lab.execution

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import java.nio.file.Path

/** Resolve only known portable roots; old manifests keep their worktree-relative interpretation. */
internal fun portablePath(value: String, source: Path, corpus: Path): Path {
    val relative = safeRelative(value)
    return when (relative.first().toString()) {
        "lab-source" -> if (relative.nameCount == 1) source else source.resolve(relative.subpath(1, relative.nameCount))
        "lab-corpus" -> if (relative.nameCount == 1) corpus else corpus.resolve(relative.subpath(1, relative.nameCount))
        else -> source.resolve(relative)
    }
}
