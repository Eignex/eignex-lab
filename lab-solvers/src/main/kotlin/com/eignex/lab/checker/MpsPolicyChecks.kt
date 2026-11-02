package com.eignex.lab.checker

import com.eignex.lab.reference.sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

/** A point verdict under one identified policy; this is never optimum or bound authority. */
@Serializable
data class MpsPointVerdict(val policy: String, val state: String, val reason: String,
                           val checkerVersion: String? = null, val checkerSha256: String? = null)

/** Independent point checks and the complete identity needed to qualify a comparison. */
@Serializable
data class MpsPolicyChecks(
    val exact: MpsPointVerdict,
    val configured: MpsPointVerdict,
    val semantics: JsonObject? = null,
    val semanticsSha256: String? = null,
    val checkerPolicySha256: String? = null,
    val comparisonIdentity: String? = null,
    val sourceSha256: String? = null,
    val witnessSha256: String,
    val claimedObjective: String? = null,
    val reconstructedObjective: String? = null,
    val objectiveSense: String? = null,
    val objectiveUnits: String = "original-mps",
    val scope: String = "original-model",
    val assumptions: List<String> = emptyList(),
    val semanticsQualified: Boolean = false,
    val declarationOrigin: String = "absent",
    val semanticsOrigin: JsonObject? = null,
)

/** A validated declaration; source numbers and witness coordinates remain rational throughout checking. */
class MpsNumericalSemantics internal constructor(
    internal val canonical: JsonObject,
    internal val absolute: MpsQ,
    internal val relative: MpsQ,
    internal val integers: Pair<MpsQ?, MpsQ?>,
    internal val reals: Pair<MpsQ?, MpsQ?>,
) {
    companion object {
        const val SCHEMA = "mps-decimal-semantics-v1"
        /** Canonical effective semantics identity, independent of point validity or numeric spelling. */
        fun identity(declaration: JsonElement?): String? = runCatching {
            sha256(read(declaration).canonical.toString().toByteArray())
        }.getOrNull()

        /** Independent checker rules plus the effective policy, without any solver proof authority. */
        fun checkerPolicyIdentity(declaration: JsonElement?): String? = identity(declaration)?.let {
            sha256("${Policies.MPS_DECLARED}\n$it\nmax(abs,rel*max(abs(bound),sum(abs(term))));bounds=abs(bound);integrality=exact;objective=exact".toByteArray())
        }

        /** Whole original MPS source, original objective units and no assumptions. */
        fun comparisonIdentity(declaration: JsonElement?, sourceSha256: String, sense: String): String? {
            if (!sourceSha256.matches(Regex("[0-9a-f]{64}")) || sense !in setOf("MIN", "MAX")) return null
            val semanticsHash = identity(declaration) ?: return null
            val policyHash = checkerPolicyIdentity(declaration) ?: return null
            return sha256("$sourceSha256\n$semanticsHash\n$policyHash\n$sense\noriginal-mps\noriginal-model\n[]".toByteArray())
        }

        internal fun read(value: JsonElement?): MpsNumericalSemantics {
            fun obj(value: JsonElement?, keys: Set<String>): JsonObject {
                val o = value as? JsonObject ?: error("missing numerical semantics object")
                require(o.keys == keys) { "incomplete or unsupported numerical semantics fields" }
                return o
            }
            fun literal(o: JsonObject, key: String, expected: String) {
                require((o[key] as? JsonPrimitive)?.content == expected) { "unsupported $key semantics" }
            }
            fun number(value: JsonElement?): MpsQ {
                val p = value as? JsonPrimitive ?: error("missing rational semantics value")
                require(p.isString && p != JsonNull) { "semantics scalars must be exact strings" }
                return MpsQ.read(p.content)
            }
            fun bounds(value: JsonElement?, integer: Boolean): Pair<Pair<MpsQ?, MpsQ?>, JsonObject> {
                val o = obj(value, setOf("lower", "upper"))
                fun endpoint(key: String) = if (o[key] == JsonNull) null else number(o[key])
                val lower = endpoint("lower"); val upper = endpoint("upper")
                require(lower == null || upper == null || lower <= upper) { "reversed default bounds" }
                require(!integer || listOfNotNull(lower, upper).all { it.d == java.math.BigInteger.ONE }) {
                    "nonintegral integer defaults"
                }
                return (lower to upper) to buildJsonObject {
                    put("lower", lower?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                    put("upper", upper?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                }
            }
            val declaration = obj(value, setOf("schema", "declared", "effective"))
            literal(declaration, "schema", SCHEMA)
            fun policy(value: JsonElement?): MpsNumericalSemantics {
                val o = obj(value, setOf("numericAuthority", "feasibilityTolerance", "undeclaredIntegers",
                    "undeclaredReals", "integrality", "objective"))
                literal(o, "numericAuthority", "DECIMAL_LITERALS")
                literal(o, "integrality", "exact"); literal(o, "objective", "exact")
                val tolerance = obj(o["feasibilityTolerance"], setOf("absolute", "relative"))
                val absolute = number(tolerance["absolute"]); val relative = number(tolerance["relative"])
                require(absolute >= MpsQ.ZERO && relative >= MpsQ.ZERO) { "negative tolerance" }
                val (integers, integerJson) = bounds(o["undeclaredIntegers"], true)
                val (reals, realJson) = bounds(o["undeclaredReals"], false)
                val canonical = buildJsonObject {
                    put("numericAuthority", "DECIMAL_LITERALS")
                    put("feasibilityTolerance", buildJsonObject {
                        put("absolute", absolute.toString()); put("relative", relative.toString())
                    })
                    put("undeclaredIntegers", integerJson); put("undeclaredReals", realJson)
                    put("integrality", "exact"); put("objective", "exact")
                }
                return MpsNumericalSemantics(canonical, absolute, relative, integers, reals)
            }
            policy(declaration["declared"])
            return policy(declaration["effective"])
        }
    }
}

/** Explicit policies apply equally to fraction, decimal and exponent spellings. */
class RationalMpsChecker(
    private val exact: FormatChecker = ExactMpsChecker(),
) : FormatChecker {
    override val format = "MPS"
    override val policy = Policies.MPS_DECLARED
    override val checker = "Eignex Lab independent rational MPS policy checker"

    override fun check(case: CheckCase, work: File): Verdict {
        val started = System.nanoTime()
        val strict = exact.check(case, work)
        val declaration = case.record["numericalSemantics"]?.takeUnless { it == JsonNull }
        val parsed = runCatching { MpsNumericalSemantics.read(declaration) }
        val semantics = parsed.getOrNull()
        val origin = case.field("numericalSemanticsOrigin") ?: if (declaration == null) "absent" else "solver-record"
        val qualified = semantics != null && origin == "solver-record"
        val configured = if (semantics == null) Verdict(LabCheck.UNKNOWN,
            "unqualified numerical policy: ${parsed.exceptionOrNull()?.message}")
        else ExactMpsChecker.configured(semantics, (120_000 - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)).check(case, work)
        val source = (case.record["sourceHashes"] as? JsonObject)?.get("model")?.jsonPrimitive?.content
        val semanticsHash = MpsNumericalSemantics.identity(declaration)
        val policyHash = MpsNumericalSemantics.checkerPolicyIdentity(declaration)
        val sense = configured.mpsSense
        val comparison = if (source != null && sense != null && configured.mpsObjective != null)
            MpsNumericalSemantics.comparisonIdentity(declaration, source, sense) else null
        fun point(v: Verdict, p: String) = MpsPointVerdict(p, v.state, v.reason, v.version, v.sha256)
        val checks = MpsPolicyChecks(
            exact = point(strict, Policies.MPS_EXACT), configured = point(configured, policy),
            semantics = declaration as? JsonObject, semanticsSha256 = semanticsHash,
            checkerPolicySha256 = policyHash, comparisonIdentity = comparison, sourceSha256 = source,
            witnessSha256 = sha256(case.witness.toByteArray()), claimedObjective = case.claimedObjective,
            reconstructedObjective = configured.mpsObjective, objectiveSense = sense,
            semanticsQualified = qualified, declarationOrigin = origin,
            semanticsOrigin = case.record["numericalSemanticsEvidence"] as? JsonObject,
        )
        return configured.copy(
            state = if (qualified) configured.state else LabCheck.UNKNOWN,
            reason = if (semantics != null && !qualified)
                "unqualified numerical policy: $origin; conditional verdict ${configured.state}: ${configured.reason}"
            else configured.reason,
            policy = policy, checker = checker, mpsChecks = checks,
        )
    }
}
