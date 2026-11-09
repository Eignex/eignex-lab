package com.eignex.lab.reference

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

internal class ExactObjective private constructor(
    private val numerator: BigInteger,
    private val denominator: BigInteger,
) : Comparable<ExactObjective> {
    override fun compareTo(other: ExactObjective): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    override fun toString(): String =
        if (denominator == BigInteger.ONE) numerator.toString() else "$numerator/$denominator"

    operator fun plus(other: ExactObjective): ExactObjective = normalized(
        numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator,
    )

    operator fun times(other: ExactObjective): ExactObjective =
        normalized(numerator * other.numerator, denominator * other.denominator)

    operator fun div(other: ExactObjective): ExactObjective =
        normalized(numerator * other.denominator, denominator * other.numerator)

    val integral: Boolean get() = denominator == BigInteger.ONE

    private fun normalized(n: BigInteger, d: BigInteger): ExactObjective = requireNotNull(reduced(n, d)) { "objective outside the exact bounds" }

    fun approximate(): Double? = BigDecimal(numerator).divide(BigDecimal(denominator), MathContext.DECIMAL128)
        .toDouble().takeIf(Double::isFinite)

    companion object {
        fun value(exact: String?, legacy: Double?): ExactObjective? =
            if (exact != null) parse(exact) else legacy?.takeIf(Double::isFinite)?.let { parse(it.toString()) }

        /** Bounds that keep a hostile or corrupt value from costing more than a parse: as the exact MPS checker's. */
        private const val MAX_CHARS = 4095
        private const val MAX_SCALE = 4095
        private const val MAX_BITS = 16_384

        fun parse(text: String): ExactObjective? = runCatching {
            require(text.length <= MAX_CHARS)
            val parts = text.trim().split('/')
            val numerator: BigInteger
            val denominator: BigInteger
            if (parts.size == 1) {
                val decimal = BigDecimal(parts.single())
                require(decimal.scale() in -MAX_SCALE..MAX_SCALE)
                numerator = decimal.unscaledValue() * BigInteger.TEN.pow((-decimal.scale()).coerceAtLeast(0))
                denominator = BigInteger.TEN.pow(decimal.scale().coerceAtLeast(0))
            } else {
                require(parts.size == 2)
                numerator = BigInteger(parts[0])
                denominator = BigInteger(parts[1])
            }
            reduced(numerator, denominator)
        }.getOrNull()

        private fun reduced(numerator: BigInteger, denominator: BigInteger): ExactObjective? {
            if (denominator.signum() == 0 || numerator.bitLength() > MAX_BITS || denominator.bitLength() > MAX_BITS) return null
            val divisor = numerator.gcd(denominator)
            val sign = BigInteger.valueOf(denominator.signum().toLong())
            return ExactObjective(numerator / divisor * sign, denominator / divisor * sign)
        }
    }
}
