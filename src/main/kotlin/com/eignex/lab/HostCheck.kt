package com.eignex.lab

import com.eignex.koblas.BuiltinEngines
import com.eignex.koblas.KoblasEngineApi
import com.eignex.koblas.koblas
import com.eignex.koblas.vendor.Vendor
import com.eignex.koblas.vendor.hostPlatform
import com.eignex.koblas.vendor.openBlas
import kotlinx.serialization.Serializable

/** What koblas resolved on this host, as logged at startup and served at `/health`. */
@Serializable
data class HostReport(
    val host: String,
    val cores: Int,
    val java: String,
    val koblasEngine: String,
    val simd: Boolean,
    val candidates: List<String>,
    val vendor: String? = null,
    val vendorLibrary: String? = null,
    val vendorVersion: String? = null,
    val vendorThreads: String? = null,
    val cpu: CpuCapabilities = NodeTags.localCapabilities,
    val nodeTags: Set<String> = emptySet(),
)

@OptIn(KoblasEngineApi::class)
fun hostReport(): HostReport {
    val host = hostPlatform()
    val blas = openBlas()
    return HostReport(
        host = host.toString(),
        cores = Runtime.getRuntime().availableProcessors(),
        java = "${System.getProperty("java.vendor")} ${System.getProperty("java.runtime.version")}",
        koblasEngine = koblas.name,
        simd = BuiltinEngines.simd != null && koblas === BuiltinEngines.simd,
        candidates = Vendor.select(host).map { it.vendorName },
        vendor = blas?.vendor?.vendorName,
        vendorLibrary = blas?.libraryPath,
        vendorVersion = blas?.version,
        vendorThreads = blas?.threadEvidence?.name,
    )
}

/**
 * Log the koblas backend and refuse to start unless its default engine is the Vector API one. klause reaches only
 * Level 1, which runs on those kernels; host BLAS serves Level 2 and 3 alone, so it is reported and not required.
 * Timings from the scalar fallback are not comparable with any other run, so a server that would produce them
 * stops before it takes a job.
 */
fun requireAcceleratedHost(): HostReport {
    val report = hostReport()
    println("koblas: host=${report.host} cores=${report.cores} java=${report.java}")
    println("koblas: engine=${report.koblasEngine} candidates=${report.candidates.joinToString()}")
    println(
        "koblas: vendor=${report.vendor ?: "none"} library=${report.vendorLibrary ?: "-"} " +
            "version=${report.vendorVersion ?: "-"} threads=${report.vendorThreads ?: "-"}",
    )
    check(report.simd) {
        "koblas runs the scalar engine on ${report.host} (${report.koblasEngine}): the Vector API is unavailable; " +
            "start the JVM with --add-modules=jdk.incubator.vector on a JDK that ships it"
    }
    return report
}
