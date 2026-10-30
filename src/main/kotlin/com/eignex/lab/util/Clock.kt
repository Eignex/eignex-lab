package com.eignex.lab.util

/** Where the lab reads the time, so code that decides by it can be handed a fixed or stepped clock in a test. */
fun interface Clock {
    /** Milliseconds since the epoch. */
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** The wall clock, for code that is not handed a [Clock]. */
fun now(): Long = Clock.SYSTEM.now()
