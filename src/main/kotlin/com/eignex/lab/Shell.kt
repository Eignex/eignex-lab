package com.eignex.lab

/** [value] as one single-quoted shell word, whatever it holds. */
internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
