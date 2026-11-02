package com.eignex.lab.analysis

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*

import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A counterexample to a native optimum, independent of the claimed point's feasibility. */
@Serializable
data class OptimumAssessment(
    val state: String,
    val reason: String,
    val objective: String? = null,
    val counterexampleObjective: String? = null,
    val witnessSha256: String? = null,
    val checkerPolicy: String? = null,
    val evidenceJob: Long? = null,
    val evidenceCase: Int? = null,
    val scopeAuthority: String? = null,
) {
    companion object {
        const val CONTRADICTED = "contradicted"
        const val UNVERIFIED = "unverified"
        const val NO_COUNTEREXAMPLE = "no-counterexample"
    }
}

/**
 * Compares original objectives of scope-matched, independently checked points with native optimum claims.
 * No search runs here, and an absence of counterexamples supplies no independent proof.
 */
object OptimumClaims {
    private const val FIELD = "labOptimumClaim"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun assessment(record: JsonElement?): OptimumAssessment? = (record as? JsonObject)?.get(FIELD)
        ?.let { runCatching { json.decodeFromJsonElement<OptimumAssessment>(it) }.getOrNull() }

    /** Derived copies only: neither a producer record nor a checking receipt is rewritten. */
    fun qualify(cases: List<CaseResult>, evidence: List<CaseResult> = emptyList()): List<CaseResult> {
        val claimedSources = cases.mapNotNull { (it.record as? JsonObject)?.takeIf(::nativeOptimum)?.get("sourceHashes") }.toSet()
        if (cases.none { (it.record as? JsonObject)?.let(::nativeOptimum) == true }) return cases
        val points = (cases + evidence).filter { it.status == Status.DONE &&
            (it.record as? JsonObject)?.get("sourceHashes") in claimedSources }.mapNotNull(::point)
            .groupBy { it.scope }
        return cases.map { case ->
            val record = case.record as? JsonObject ?: return@map case
            if (!nativeOptimum(record)) return@map case
            val claim = scope(case)
            val value = objective(record)
            val result = when {
                claim == null || value == null -> OptimumAssessment(OptimumAssessment.UNVERIFIED,
                    "original source, effective semantics, objective mapping or claim scope unavailable")
                else -> {
                    val mapped = mapping(record)?.apply(value)
                    val better = mapped?.let { bound -> points[claim].orEmpty().filter {
                        if (claim.maximize) it.objective > bound else it.objective < bound
                    }.minWithOrNull { a, b -> a.objective.compareTo(b.objective) * if (claim.maximize) -1 else 1 } }
                    when {
                        mapped == null -> OptimumAssessment(OptimumAssessment.UNVERIFIED, "objective mapping unavailable")
                        points[claim].isNullOrEmpty() -> OptimumAssessment(OptimumAssessment.UNVERIFIED,
                            "no independently checked point bound to this claim scope", mapped.toString())
                        better == null -> OptimumAssessment(OptimumAssessment.NO_COUNTEREXAMPLE,
                            "no scope-matched checked better point in the supplied evidence; native proof remains independently unverified", mapped.toString())
                        else -> OptimumAssessment(OptimumAssessment.CONTRADICTED,
                            "a scope-matched independently checked original-source point improves the native optimum",
                            mapped.toString(), better.objective.toString(), better.witness, better.policy,
                            better.case.origin?.job, better.case.index, claim.authority)
                    }
                }
            }
            case.copy(record = JsonObject(record + (FIELD to json.encodeToJsonElement(result))))
        }
    }

    /** Nested scoring reuses the assessment supplied by its enclosing, wider evidence query. */
    internal fun local(cases: List<CaseResult>): List<CaseResult> {
        val fresh = qualify(cases)
        return cases.zip(fresh) { original, computed -> if (assessment(original.record) != null) original else computed }
    }

    /** All retained points for the claimed problems, including other seeds, arms and revisions. */
    fun qualify(store: Store, cases: List<CaseResult>): List<CaseResult> {
        val claimed = cases.filter { (it.record as? JsonObject)?.let(::nativeOptimum) == true }
            .distinctBy { it.problem.referenceKey }
        val evidence = claimed.flatMap { case ->
            val key = case.problem.referenceKey
            store.problemRuns(key.collection, key.problem, withWitness = true).map { run ->
                case.copy(index = run.case, status = run.status, record = run.record, arm = run.arm,
                    seed = run.seed, repeat = run.repeat, origin = CaseOrigin(run.job, null))
            }
        }
        return qualify(cases, evidence)
    }

    private data class Scope(val sources: JsonObject, val semantics: String, val maximize: Boolean,
                             val units: String, val objective: String, val assumptions: JsonArray,
                             val authority: String = "declared-scope")
    private data class Mapping(val scale: Q, val offset: Q) {
        fun apply(value: Q) = scale * value + offset
    }
    private data class Point(val scope: Scope, val objective: Q, val witness: String, val policy: String, val case: CaseResult)

    private fun nativeOptimum(record: JsonObject): Boolean = (text(record, "solver") != null || record.containsKey("optimalityScope")) &&
        text(record, "kind") == "optimize" &&
        (text(record, "proven") == "true" || text(record["stats"] as? JsonObject, "reportedProven") == "true") &&
        (text(record, "feasible") == "true" || text(record["stats"] as? JsonObject, "reportedFeasible") == "true")

    private fun scope(case: CaseResult): Scope? {
        val record = case.record as? JsonObject ?: return null
        if (text(record["stats"] as? JsonObject, "floatApproximation") == "true") return null
        val sources = record["sourceHashes"] as? JsonObject ?: return null
        if (sources.isEmpty() || sources.values.any { (it as? JsonPrimitive)?.content?.matches(HASH) != true }) return null
        val explicit = record["optimalityScope"] as? JsonObject
        if (explicit != null) {
            if (text(explicit, "schema") != "original-source-optimum-v1") return null
            val semantics = text(explicit, "semanticsSha256")?.takeIf { HASH.matches(it) } ?: return null
            val objective = explicit["objective"] as? JsonObject ?: return null
            val sense = text(objective, "sense")?.takeIf { it in setOf("min", "max") } ?: return null
            val units = text(objective, "units")?.takeIf { it.isNotBlank() } ?: return null
            val identity = text(objective, "identity")?.takeIf { it.isNotBlank() } ?: return null
            val assumptions = explicit["assumptions"] as? JsonArray ?: return null
            val map = mapping(record) ?: return null
            val nativeMaximize = text(record, "maximize")?.toBooleanStrictOrNull() ?: return null
            if ((nativeMaximize xor (map.scale < Q.ZERO)) != (sense == "max")) return null
            return Scope(sources, semantics, sense == "max", units, identity, assumptions)
        }
        if (case.problem.format == "MPS" && !record.containsKey("optimalityScope") && !record.containsKey("objectiveMapping")) {
            if (sources.keys != setOf("model")) return null
            val semantics = MpsNumericalSemantics.identity(record["numericalSemantics"]) ?: return null
            val maximize = text(record, "maximize")?.toBooleanStrictOrNull() ?: return null
            val receipt = (record["labCheck"] as? JsonObject)?.get("mpsChecks") as? JsonObject ?: return null
            if (text(receipt, "objectiveSense") != if (maximize) "MAX" else "MIN") return null
            if (text(receipt, "sourceSha256") != text(sources, "model") || text(receipt, "semanticsSha256") != semantics ||
                text(receipt, "scope") != "original-model" || receipt["assumptions"] != JsonArray(emptyList())) return null
            return Scope(sources, semantics, maximize, "original-mps", "source-objective", JsonArray(emptyList()), "published-source-contract")
        }
        // The XCSP3 integer adapter reports whole-original-source costs, checked by the official cost checker.
        // Other semantics, private scopes and mapped objectives require explicit bound checking receipts.
        if (case.problem.format != "XCSP3" || text(record, "solver") != "klause" ||
            text(record["comparisonControls"] as? JsonObject, "exact") != "true" ||
            record.containsKey("objectiveMapping") || record.containsKey("optimalityScope")) return null
        val maximize = text(record, "maximize")?.toBooleanStrictOrNull() ?: return null
        return Scope(sources, "xcsp3-integer-source-v1", maximize, "original-source", "source-objective", JsonArray(emptyList()), "published-source-contract")
    }

    private fun point(case: CaseResult): Point? {
        val record = case.record as? JsonObject ?: return null
        if (text(record, "kind") != "optimize") return null
        if (text(record, "feasible") != "true" && text(record["stats"] as? JsonObject, "reportedFeasible") != "true") return null
        val scope = scope(case) ?: return null
        val check = record["labCheck"] as? JsonObject ?: return null
        if (text(check, "state") != LabCheck.VALID || check["sourceSha256"] != scope.sources) return null
        val witness = text(check, "witnessSha256")?.takeIf { HASH.matches(it) } ?: return null
        val retainedWitness = text(record, "finalWitness") ?: return null
        if (digest(retainedWitness) != witness) return null
        val policy = text(check, "policy")?.takeIf { it.isNotBlank() } ?: return null
        if (text(check, "checkerSha256")?.matches(HASH) != true) return null
        val value = objective(record)?.let { mapping(record)?.apply(it) } ?: return null
        if (scope.semantics == "xcsp3-integer-source-v1") {
            if (policy != Policies.XCSP3 || value.d != BigInteger.ONE) return null
        } else if (case.problem.format == "MPS" && !record.containsKey("optimalityScope")) {
            val receipt = check["mpsChecks"] as? JsonObject ?: return null
            val configured = receipt["configured"] as? JsonObject ?: return null
            val declaration = record["numericalSemantics"]
            val sense = if (scope.maximize) "MAX" else "MIN"
            val source = text(scope.sources, "model") ?: return null
            if (policy != Policies.MPS_DECLARED || text(configured, "state") != LabCheck.VALID || text(configured, "policy") != Policies.MPS_DECLARED ||
                text(receipt, "semanticsSha256") != scope.semantics ||
                text(receipt, "checkerPolicySha256") != MpsNumericalSemantics.checkerPolicyIdentity(declaration) ||
                text(receipt, "comparisonIdentity") != MpsNumericalSemantics.comparisonIdentity(declaration, source, sense) ||
                text(receipt, "sourceSha256") != source || text(receipt, "witnessSha256") != witness ||
                text(receipt, "objectiveSense") != sense || text(receipt, "objectiveUnits") != "original-mps" ||
                text(receipt, "scope") != "original-model" || receipt["assumptions"] != JsonArray(emptyList()) ||
                Q.parse(text(receipt, "claimedObjective")) != value || Q.parse(text(receipt, "reconstructedObjective")) != value) return null
        } else {
            val bound = check["optimumEvidence"] as? JsonObject ?: return null
            if (bound["scope"] != record["optimalityScope"] || Q.parse(text(bound, "objective")) != value) return null
        }
        return Point(scope, value, witness, policy, case)
    }

    private fun mapping(record: JsonObject): Mapping? {
        if (!record.containsKey("objectiveMapping")) return Mapping(Q.ONE, Q.ZERO)
        val map = record["objectiveMapping"] as? JsonObject ?: return null
        val scale = Q.parse(text(map, "scale"))?.takeUnless { it == Q.ZERO } ?: return null
        val offset = Q.parse(text(map, "offset")) ?: return null
        return Mapping(scale, offset)
    }

    private fun objective(record: JsonObject): Q? {
        val stats = record["stats"] as? JsonObject
        val restored = Outcome.withdrawn(record)
        val exact = if (restored) stats?.get("reportedExactObjective") else record["exactObjective"]
        if (exact != null && exact !is JsonNull) return Q.parse((exact as? JsonPrimitive)?.content)
        return Q.parse(text(if (restored) stats else record, if (restored) "reportedObjective" else "objective"))
    }

    private fun text(value: JsonObject?, key: String) = (value?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private val HASH = Regex("[0-9a-f]{64}")

    private data class Q private constructor(val n: BigInteger, val d: BigInteger) : Comparable<Q> {
        override fun compareTo(other: Q) = (n * other.d).compareTo(other.n * d)
        operator fun plus(other: Q) = of(n * other.d + other.n * d, d * other.d)
        operator fun times(other: Q) = of(n * other.n, d * other.d)
        override fun toString() = if (d == BigInteger.ONE) "$n" else "$n/$d"
        companion object {
            val ZERO = of(BigInteger.ZERO, BigInteger.ONE)
            val ONE = of(BigInteger.ONE, BigInteger.ONE)
            private fun of(n: BigInteger, d: BigInteger): Q {
                val gcd = n.gcd(d)
                return Q(n / gcd, d / gcd)
            }
            fun parse(text: String?): Q? = runCatching {
                require(text != null && text.length <= 4096)
                if ('/' in text) {
                    require(text.matches(Regex("[+-]?[0-9]+/[0-9]+")))
                    val parts = text.split('/')
                    val d = BigInteger(parts[1])
                    require(d.signum() > 0)
                    of(BigInteger(parts[0]), d)
                } else {
                    val value = BigDecimal(text)
                    require(value.scale() in -4096..4096)
                    if (value.scale() >= 0) of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
                    else of(value.unscaledValue() * BigInteger.TEN.pow(-value.scale()), BigInteger.ONE)
                }
            }.getOrNull()
        }
    }
}
