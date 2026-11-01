package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsBufferRecoveryTest {
    @Test fun onlySustainedActiveBufferingTriggersRecovery() {
        val recovery = SportsBufferRecovery()
        assertFalse(recovery.due("game", "a", 0, true, false, 15000))
        assertFalse(recovery.due("game", "a", 1000, true, true, 15000))
        assertFalse(recovery.due("game", "a", 15999, true, true, 15000))
        assertTrue(recovery.due("game", "a", 16000, true, true, 15000))
        assertFalse(recovery.due("game", "a", 17000, false, true, 15000))
        assertFalse(recovery.due("game", "a", 18000, true, true, 15000))
        assertFalse(recovery.due("game", "a", 32000, true, true, 15000))
        recovery.pause()
        assertFalse(recovery.due("game", "a", 33000, true, true, 15000))
        assertFalse(recovery.due(null, "a", 100000, true, true, 15000))
    }
    @Test fun switchCooldownVisitedFeedsAndSessionBudgetPreventLoops() {
        val recovery = SportsBufferRecovery()
        recovery.due("game", "a", 0, true, true, 10000)
        assertTrue(recovery.due("game", "a", 10000, true, true, 10000))
        recovery.switched("a", "b", 10000); assertFalse(recovery.allows("a")); assertFalse(recovery.allows("b"))
        recovery.due("game", "b", 11000, true, true, 10000)
        assertFalse(recovery.due("game", "b", 39999, true, true, 10000))
        assertTrue(recovery.due("game", "b", 40000, true, true, 10000))
        recovery.switched("b", "c", 40000)
        recovery.due("game", "c", 41000, true, true, 10000)
        assertTrue(recovery.due("game", "c", 70000, true, true, 10000))
        recovery.switched("c", "d", 70000)
        recovery.due("game", "d", 71000, true, true, 10000)
        assertFalse(recovery.due("game", "d", 120000, true, true, 10000))
        recovery.due("other game", "a", 121000, true, true, 10000)
        assertTrue(recovery.due("other game", "a", 131000, true, true, 10000))
    }
}
