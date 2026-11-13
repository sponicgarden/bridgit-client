package cc.bridgit.app.core.spending

import cc.bridgit.app.core.contract.ContractVocab
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Money, units, namespaces, spend ids and refusal ids against the contract pack (A0300 SA2.1). */
class MoneyTest {
    private fun pack(path: String) = JSONObject(javaClass.classLoader!!.getResource("client-core/$path")!!.readText())
    private fun usd(atoms: Long) = Money("USD-6", atoms)

    @Test fun unitCasesOfThePackParseTheSameHere() {
        val cases = pack("vocab/units.json").getJSONArray("cases")
        assertTrue(cases.length() > 10)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val input = c.getJSONObject("in")
            val got = Money.parse(input.getString("unit"), input.getString("amount"))?.atoms?.toString()
            assertEquals("case $i ${input}", if (c.isNull("out")) null else c.getString("out"), got)
        }
    }

    @Test fun roundingIsAlwaysAgainstTheSpender() {
        // A cost rounds up, something coming back rounds down.
        assertEquals(usd(1), Money.parse("USD-6", "0.0000001", Rounding.COST_UP))
        assertEquals(usd(0), Money.parse("USD-6", "0.0000001", Rounding.RETURN_DOWN))
        assertEquals(usd(34), usd(100).scaled(1, 3, Rounding.COST_UP))
        assertEquals(usd(33), usd(100).scaled(1, 3, Rounding.RETURN_DOWN))
        assertEquals(usd(50), usd(100).scaled(1, 2, Rounding.COST_UP))
        assertEquals(usd(50), usd(100).scaled(1, 2, Rounding.RETURN_DOWN))
        // Below zero the same directions hold: up is toward more owed.
        assertEquals(usd(-33), usd(-100).scaled(1, 3, Rounding.COST_UP))
        assertEquals(usd(-34), usd(-100).scaled(1, 3, Rounding.RETURN_DOWN))
        assertEquals(usd(0), usd(7).scaled(0, 5, Rounding.COST_UP))
    }

    @Test fun overflowThrowsAndNeverWraps() {
        assertThrows(MoneyException::class.java) { usd(Long.MAX_VALUE) + usd(1) }
        assertThrows(MoneyException::class.java) { usd(Long.MIN_VALUE) - usd(1) }
        assertThrows(MoneyException::class.java) { usd(Long.MAX_VALUE).scaled(2, 1, Rounding.COST_UP) }
        assertEquals(usd(Long.MAX_VALUE), usd(Long.MAX_VALUE).scaled(Long.MAX_VALUE, Long.MAX_VALUE, Rounding.COST_UP))
        assertThrows(MoneyException::class.java) { usd(1).scaled(1, 0, Rounding.COST_UP) }
        assertThrows(MoneyException::class.java) { usd(1).scaled(-1, 2, Rounding.COST_UP) }
        assertNull(Money.parse("USD-6", "9223372036854.775808"))
    }

    @Test fun unitsNeverMixAndUnknownUnitsDoNotExist() {
        assertThrows(MoneyException::class.java) { Money("EUR-2", 1) }
        assertThrows(MoneyException::class.java) { Money("usd-6", 1) }
        assertEquals(listOf("USD-6"), ContractVocab.MONEY_UNITS.map { it.id })
        // Only one unit exists so far, so a sum of two real units cannot be formed in a test yet.
    }

    @Test fun amountsAreNotNegativeButRoomCanBe() {
        assertThrows(MoneyException::class.java) { usd(-1).requireAmount() }
        assertTrue((usd(5) - usd(9)).isNegative)
        assertTrue(usd(5) < usd(9))
        assertEquals("1.500000", usd(1_500_000).toDecimal())
        assertEquals("0.000001", usd(1).toDecimal())
        assertEquals("-0.000004", usd(-4).toDecimal())
    }

    @Test fun theInstrumentFixesTheNamespace() {
        val cases = pack("vocab/instruments.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals("case $i", c.getString("out"), Namespace.of(c.getString("in")).id)
        }
        assertEquals(ContractVocab.SPEND_NAMESPACES, Namespace.values().map { it.id })
        assertEquals(Namespace.SANDBOX, Namespace.claimed("sandbox"))
        assertNull(Namespace.claimed("test"))
        assertNull(Namespace.claimed("LIVE"))
    }

    @Test fun spendIdsAreTheContractBytes() {
        // The same two values are in tools/client-core/tests/spending.test.mjs.
        assertEquals("9d1a6d79256e5f5b0d4c9472e227b4ad19995a12a70f7a5597bc9ef271f2e476", SpendId.of(Namespace.LIVE, "install-a", "req-1"))
        assertEquals("236e754a1935e7817f3722b701a628cfba4bcafd4abb8538bb5e4989bcdffa66", SpendId.of(Namespace.SANDBOX, "install-a", "req-1"))
        assertNotEquals(SpendId.of(Namespace.LIVE, "ab", "c"), SpendId.of(Namespace.LIVE, "a", "bc"))
        assertTrue(SpendId.isValid(SpendId.of(Namespace.LIVE, "i", "r")))
        assertFalse(SpendId.isValid("req-1"))
        assertThrows(IllegalArgumentException::class.java) { SpendId.of(Namespace.LIVE, "", "r") }
    }

    @Test fun entryHashIsTheContractBytes() {
        // The first entry of tools/client-core/tests/spending.test.mjs, hashed the same way.
        val first = mapOf(
            "v" to "entry/1", "kind" to "epoch", "namespace" to "sandbox", "epoch" to "00112233445566778899aabbccddeeff", "seq" to 1L,
            "prev" to "0".repeat(64), "t_wall" to 1794000000000L, "t_elapsed" to 5000L, "boot" to 7L, "install" to "install-a", "origin" to "first",
        )
        assertEquals("df7c94daf62236ec9b0bf93cbb5990b8efdd723db64b5138fdcd17c45d991912", Journal.hashOf(Canonical.encode(first)))
        assertEquals("""{"a":[1,true,null,"x\"\\\n"],"b":{"c":-2}}""", Canonical.encode(mapOf("b" to mapOf("c" to -2L), "a" to listOf(1, true, null, "x\"\\\n"))))
        assertThrows(IllegalArgumentException::class.java) { Canonical.encode(mapOf("a" to 1.5)) }
    }

    @Test fun everyRefusalCodeIsAGuideEntryOfThePackAndOnlyWhatADoorReturnsIsBuilt() {
        val codes = pack("errors.json").getJSONObject("codes")
        // The configuration API refuses a budget or a cap that names an instrument of the other namespace. No door runs a spend yet.
        val returned = setOf(SpendRefusal.Code.NAMESPACE_MISMATCH)
        for (code in SpendRefusal.Code.values()) {
            val entry = codes.optJSONObject(code.id) ?: throw AssertionError("${code.id} is not in errors.json")
            assertTrue(code.id, entry.has("guide"))
            assertEquals("${code.id}: built only when a door returns it", code in returned, entry.getBoolean("built"))
        }
        // The codes this build gives a refused spend by rule (1 to 6; rule 7 is rate_limited) are entries too, and no door returns them yet.
        for (code in listOf("spending_off", "spend_not_allowed", "spend_source_not_allowed", "spend_over_cap", "spend_bridgit_cap", "spend_over_budget")) {
            assertFalse(code, codes.getJSONObject(code).getBoolean("built"))
        }
        // Reasons ride beside a code; none of them is itself an error code.
        for (reason in SpendRefusal.Reason.values()) assertFalse(reason.id, codes.has(reason.id))
    }
}
