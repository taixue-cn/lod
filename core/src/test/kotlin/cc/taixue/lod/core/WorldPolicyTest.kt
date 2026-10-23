package cc.taixue.lod.core

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorldPolicyTest {
    @Test
    fun whitelistIsOptInAndBlacklistIsOptOut() {
        assertFalse(WorldPolicy("whitelist", emptyList()).allows("city"))
        assertTrue(WorldPolicy("blacklist", emptyList()).allows("city"))
        assertTrue(WorldPolicy("whitelist", listOf("city")).allows("City"))
        assertFalse(WorldPolicy("blacklist", listOf("secret-*")).allows("secret-copy"))
        assertTrue(WorldPolicy("blacklist", listOf("secret-*")).allows("public"))
    }

    @Test
    fun exactEntriesAreNotRegularExpressionsAndUuidCanBeUsed() {
        val id = UUID.randomUUID().toString()
        val policy = WorldPolicy("whitelist", listOf("a.b", id))
        assertFalse(policy.allows("axb"))
        assertTrue(policy.allows("new-name", id))
        assertThrows<IllegalArgumentException> { WorldPolicy("typo", emptyList()) }
    }

    @Test
    fun playerPreferenceOnlyNarrowsServerPolicy() {
        val server = WorldPolicy("whitelist", listOf("public"))
        val player = WorldPolicy("blacklist", emptyList())
        assertFalse(server.allows("secret") && player.allows("secret"))
    }
}
