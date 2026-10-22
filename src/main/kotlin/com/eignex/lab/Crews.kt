package com.eignex.lab

/**
 * The hosts working on each shared job in this process: the local machine ([ExecutionHost.LOCAL]) and the cloud nodes
 * (their instance ids), for the jobs whose iterations they pull from the store ([Store.claimIteration]). A host joins
 * a job when it takes or joins it, and leaves once it has no more of it to run, saying how its part ended; the last to
 * leave ends the job as all their parts say ([Runner.leave]). Everything that joins or leaves, and whatever decides
 * which job a host takes, holds [lock], so no host joins a job while its last one ends it.
 */
internal class Crews {
    /** Held while a host chooses and joins a job, and while one leaves it. */
    val lock = Any()

    /** One job's hosts, how each one's part ended, and the failure that ends it, if one did. */
    class Crew {
        val hosts = LinkedHashSet<String>()
        val outcomes = ArrayList<Dispatched>()
        @Volatile
        var failure: Throwable? = null
    }

    private val crews = HashMap<Long, Crew>()

    fun join(jobId: Long, host: String) = synchronized(lock) { crews.getOrPut(jobId, ::Crew).hosts += host }

    /** The hosts on [jobId] now. */
    fun hosts(jobId: Long): Set<String> = synchronized(lock) { crews[jobId]?.hosts?.toSet().orEmpty() }

    /** Whether a host on [jobId] failed it: the others take no more of its iterations, and nothing joins it. */
    fun failed(jobId: Long): Boolean = synchronized(lock) { crews[jobId]?.failure != null }

    /** The jobs a failure ends once their hosts leave, which no host joins meanwhile. */
    fun failing(): Set<Long> = synchronized(lock) { crews.filterValues { it.failure != null }.keys.toSet() }

    /** A host's part of [jobId] failed it: the job fails once every host has left. */
    fun fail(jobId: Long, error: Throwable) = synchronized(lock) {
        crews.getOrPut(jobId, ::Crew).let { if (it.failure == null) it.failure = error }
    }

    /** [host] leaves [jobId], its part ending [outcome] (null when it ran none). The crew when it was the last, for
     *  the caller to end the job by; null while others stay on it. */
    fun leave(jobId: Long, host: String, outcome: Dispatched?): Crew? = synchronized(lock) {
        val crew = crews[jobId] ?: return@synchronized null
        crew.hosts -= host
        outcome?.let { crew.outcomes += it }
        if (crew.hosts.isNotEmpty()) return@synchronized null
        crews.remove(jobId)
        crew
    }
}
