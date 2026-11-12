package com.studycloud.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {

    private fun manifest(
        rules: RuleManifestEntry? = RuleManifestEntry("profile-1", 3),
        banks: List<BankManifestEntry> = emptyList(),
    ) = SyncManifest(
        serverTimeMillis = 1_793_000_000_000,
        rules = rules,
        banks = banks,
        nextSyncAfterSeconds = 900,
        fingerprint = "abc123",
    )

    private fun bank(id: String, version: Int, checksum: String = "sum-$id") =
        BankManifestEntry(id, "题库 $id", version, 10, checksum)

    @Test
    fun `first run pulls everything in full`() {
        val plan = SyncPlanner.plan(
            manifest(banks = listOf(bank("a", 2), bank("b", 1))),
            LocalSyncState(ruleVersion = null, bankVersions = emptyMap()),
        )

        assertTrue(plan.refreshRules)
        assertEquals(2, plan.banksToFetch.size)
        assertTrue(plan.banksToFetch.all { it.isFullSync })
        assertTrue(plan.bankIdsToDrop.isEmpty())
    }

    @Test
    fun `nothing to do when versions match`() {
        val plan = SyncPlanner.plan(
            manifest(banks = listOf(bank("a", 2))),
            LocalSyncState(ruleVersion = 3, bankVersions = mapOf("a" to 2)),
        )

        assertTrue(plan.isUpToDate)
    }

    @Test
    fun `only the changed bank is fetched incrementally`() {
        val plan = SyncPlanner.plan(
            manifest(banks = listOf(bank("a", 5), bank("b", 1))),
            LocalSyncState(ruleVersion = 3, bankVersions = mapOf("a" to 3, "b" to 1)),
        )

        assertFalse(plan.refreshRules)

        val fetch = plan.banksToFetch.single()
        assertEquals("a", fetch.bankId)
        assertEquals(3, fetch.sinceVersion)
        assertEquals(5, fetch.targetVersion)
        assertFalse(fetch.isFullSync)
    }

    @Test
    fun `local version ahead of server falls back to a full sync`() {
        val plan = SyncPlanner.plan(
            manifest(banks = listOf(bank("a", 2))),
            LocalSyncState(ruleVersion = 3, bankVersions = mapOf("a" to 9)),
        )

        assertTrue(plan.banksToFetch.single().isFullSync)
    }

    @Test
    fun `unassigned banks are dropped locally`() {
        val plan = SyncPlanner.plan(
            manifest(banks = listOf(bank("a", 2))),
            LocalSyncState(ruleVersion = 3, bankVersions = mapOf("a" to 2, "stale" to 1)),
        )

        assertEquals(listOf("stale"), plan.bankIdsToDrop)
    }

    @Test
    fun `rule version change is detected on its own`() {
        val plan = SyncPlanner.plan(
            manifest(rules = RuleManifestEntry("profile-1", 4), banks = listOf(bank("a", 2))),
            LocalSyncState(ruleVersion = 3, bankVersions = mapOf("a" to 2)),
        )

        assertTrue(plan.refreshRules)
        assertTrue(plan.banksToFetch.isEmpty())
    }

    @Test
    fun `checksum mismatch forces a full resync`() {
        assertTrue(SyncPlanner.needsFullResync("abc", "def"))
        assertFalse(SyncPlanner.needsFullResync("abc", "ABC"))
        assertFalse(SyncPlanner.needsFullResync("", "anything"))
    }
}
