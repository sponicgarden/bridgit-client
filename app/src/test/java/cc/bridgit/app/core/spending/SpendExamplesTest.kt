package cc.bridgit.app.core.spending

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * `contracts/client-core/spending/examples.json` is what this code writes, byte for byte: one small run of
 * two installs with fixed keys and a fixed clock. The pack's own test (`tools/client-core/tests/spending.test.mjs`)
 * validates the same file against the schemas and recomputes every id, digest and entry hash, so the two
 * cannot drift apart. The signatures in it are Ed25519 by test keys: the pack fixes the bytes that are
 * signed, not the install key's algorithm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpendExamplesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val world = SpendingWorld(app, seeded = true)

    @After fun close() = world.close()

    @Test fun thePackExamplesAreWhatThisCodeProduces() {
        val a = world.device("install-a")
        val b = world.device("install-b")
        world.found(a, mapOf("day" to RuleDocument.budget("account", "acct-1", "day", "USD-6", world.usd(100), mapOf("install-a" to world.usd(60), "install-b" to world.usd(40)), ceiling = world.usd(150), headroom = mapOf("install-a" to world.usd(30)))))
        assertTrue(a.held(world.usd(1)))
        a.anchor(world.start + 1_000)
        val transfer = (a.core.transfers.give("day", "install-b", "portion", world.money(10), "standing", true, world.agent, true) as Transfers.Given.Signed).json
        val increment = (a.core.increments.raise("day", world.money(20), "standing", world.agent, true) as Increments.Made.Signed).json
        val right = (a.core.transfers.give("day", "install-b", "reclaim", null, "standing", true, world.agent, true) as Transfers.Given.Signed).json
        assertEquals(Transfers.Took.TAKEN, b.core.transfers.take(transfer).result)
        assertEquals(Increments.Applied.APPLIED, b.core.increments.apply(increment).result)
        assertEquals(Transfers.Took.TAKEN, b.core.transfers.take(right).result)
        val holdings = b.core.transfers.holdings("day", "install-a", a.journal.head().epoch, "0f".repeat(16))
        val doc = a.core.document()!!
        val slice = mapOf(
            "v" to "allowance/1", "id" to "slice-1", "issuer" to "cloud", "key" to "allowance-1", "account" to "acct-1", "device" to "install-a", "instrument" to "sandbox", "source" to "key-1",
            "lines" to listOf(mapOf("rule" to "day", "period" to "day:2026-11-11", "amount" to mapOf("unit" to "USD-6", "atoms" to world.usd(5)))),
            "rules_generation" to doc.generation, "rules_digest" to doc.digest, "seq" to 2L, "journal_epoch" to a.journal.head().epoch, "issued_at" to world.start, "valid_for_s" to 3600L,
        ).let { it + ("signature" to Signed.encode(world.signAsCloud(Signed.bytes(Signed.ALLOWANCE, it)))) }

        val entries = a.journal.entries()
        a.db.close()
        val head = SQLiteDatabase.openDatabase(app.getDatabasePath("spending-sandbox-install-a.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT body FROM head", null).use { c -> c.moveToFirst(); c.getString(0) }
        }
        a.open()

        val made = Canonical.encode(
            mapOf(
                "note" to "Written by SpendExamplesTest (Android). Do not edit by hand: run the test and copy app/build/spend-examples.json here.",
                "rules" to doc.map, "rules_digest" to doc.digest,
                "transfer" to Tree.parse(transfer), "transfer_digest" to Signed.digest(Signed.TRANSFER, Tree.parse(transfer)),
                "reclaim" to Tree.parse(right),
                "increment" to Tree.parse(increment),
                "allowance" to slice,
                "holdings" to Tree.parse(holdings),
                "head" to Tree.parse(head),
                "entries" to entries.map { mapOf("hash" to it.hash, "entry" to it.tree) },
            ),
        )
        // The ids the pack's own test holds as fixed bytes (tools/client-core/tests/spending.test.mjs).
        assertEquals("cf99d72baf701f0df3060c1e8716b98a820fadea22c4784d1ba568cdfb32dff3", Tree.parse(transfer).str("id"))
        assertEquals("e0263a579979e4d6f9bbb6436efd1e5b1316a2e3facc881a914adcb080db0741", Tree.parse(increment).str("id"))
        assertEquals("d8dc9a5ce2dcaf88c45071d5fef15df48985e274b04068e48a3b63ac1ad508f5", doc.digest)
        assertEquals("7c190cfd6cf67b02d0fb942c66cb96e39d4b7362e66ca7d1d3ab2cd1526df6a9", Signed.id(Signed.RECLAIM_ID, "install-c", "sandbox", "day"))
        // Every kind of entry this small run can write is in it.
        assertEquals(listOf("epoch", "rules", "period", "allowance", "spend", "anchor", "transfer_out", "increment", "transfer_out"), entries.map { it.kind })
        File("build").mkdirs()
        File("build/spend-examples.json").writeText(JSONObject(made).toString(2) + "\n")
        val file = File("../contracts/client-core/spending/examples.json").takeIf { it.exists() } ?: File("contracts/client-core/spending/examples.json")
        assertTrue("the pack has no spending/examples.json yet: copy app/build/spend-examples.json there", file.exists())
        assertEquals("the pack's examples are not what this code writes; copy app/build/spend-examples.json over it if the change is meant", made, Canonical.encode(Tree.parse(file.readText())))
    }
}
