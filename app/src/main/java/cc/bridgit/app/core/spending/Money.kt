package cc.bridgit.app.core.spending

import cc.bridgit.app.core.contract.ContractVocab
import java.math.BigInteger

/** A money sum that cannot be formed or computed exactly: an unknown unit, two units mixed, or a count past 64 bits. */
class MoneyException(message: String) : ArithmeticException(message)

/**
 * Which way an inexact figure goes. Every rounding is **against the spender** (spending authority G2.2):
 * what a spend costs or holds rounds up, what comes back to the spender rounds down.
 */
enum class Rounding { COST_UP, RETURN_DOWN }

/**
 * Money is a unit id from `vocab/units.json` and a whole number of that unit's smallest part
 * (spending authority G2.2, review P4). There is no floating-point number anywhere: sums are exact and a
 * result that does not fit 64 bits throws instead of wrapping. [atoms] may be negative, which is how room
 * left after an overshoot reads (G5.4); an amount that is held, quoted or billed is checked with [requireAmount].
 */
data class Money(val unit: String, val atoms: Long) : Comparable<Money> {
    init { if (Units.of(unit) == null) throw MoneyException("Unknown money unit") }

    val isNegative get() = atoms < 0
    val isZero get() = atoms == 0L

    operator fun plus(other: Money) = Money(unit, exact { Math.addExact(atoms, same(other).atoms) })
    operator fun minus(other: Money) = Money(unit, exact { Math.subtractExact(atoms, same(other).atoms) })
    override fun compareTo(other: Money) = atoms.compareTo(same(other).atoms)

    /** This amount times [numerator] over [denominator], rounded as [rounding] says. Both must be positive counts. */
    fun scaled(numerator: Long, denominator: Long, rounding: Rounding): Money {
        if (numerator < 0 || denominator <= 0) throw MoneyException("A scale is a non-negative count over a positive one")
        val product = BigInteger.valueOf(atoms).multiply(BigInteger.valueOf(numerator))
        val (q, r) = product.divideAndRemainder(BigInteger.valueOf(denominator))
        // divideAndRemainder truncates toward zero; move to the floor, then up when the spender is charged.
        val floor = if (r.signum() < 0) q.subtract(BigInteger.ONE) else q
        val out = if (r.signum() != 0 && rounding == Rounding.COST_UP) floor.add(BigInteger.ONE) else floor
        if (out.bitLength() > 63) throw MoneyException("Money overflow")
        return Money(unit, out.toLong())
    }

    /** This amount, refused unless it is zero or more: what a quote, hold, bill or refund must be. */
    fun requireAmount(): Money { if (atoms < 0) throw MoneyException("An amount cannot be negative"); return this }

    /** Plain decimal text, exact: `1.500000` for 1,500,000 atoms of USD-6. For reading, never for arithmetic. */
    fun toDecimal(): String {
        val decimals = Units.of(unit)!!.decimals
        val digits = BigInteger.valueOf(atoms).abs().toString().padStart(decimals + 1, '0')
        val whole = digits.dropLast(decimals)
        return (if (atoms < 0) "-" else "") + if (decimals == 0) whole else "$whole.${digits.takeLast(decimals)}"
    }

    private fun same(other: Money): Money { if (other.unit != unit) throw MoneyException("Two money units in one sum"); return other }

    private inline fun exact(f: () -> Long): Long = try { f() } catch (e: ArithmeticException) { throw MoneyException("Money overflow") }

    companion object {
        private val DECIMAL = Regex("^(\\d+)(?:\\.(\\d+))?$")

        fun zero(unit: String) = Money(unit, 0)

        /**
         * A plain decimal amount (digits, at most one point, no sign, no exponent) as atoms of [unit], or null
         * when the unit is unknown, the text is malformed or the count passes 64 bits. Digits past the unit's
         * decimals round as [rounding] says. The cases of `vocab/units.json` are this function with COST_UP.
         */
        fun parse(unit: String, amount: String, rounding: Rounding = Rounding.COST_UP): Money? {
            val u = Units.of(unit) ?: return null
            val m = DECIMAL.matchEntire(amount) ?: return null
            val fraction = m.groupValues[2]
            var atoms = BigInteger(m.groupValues[1] + fraction.take(u.decimals).padEnd(u.decimals, '0'))
            if (rounding == Rounding.COST_UP && fraction.drop(u.decimals).any { it != '0' }) atoms = atoms.add(BigInteger.ONE)
            return if (atoms.bitLength() > 63) null else Money(unit, atoms.toLong())
        }
    }
}

/** The units of `vocab/units.json`. */
object Units {
    private val byId = ContractVocab.MONEY_UNITS.associateBy { it.id }
    fun of(id: String): ContractVocab.MoneyUnit? = byId[id]
}
