package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class ProgrammeBudgetTest {
    @Test fun overflowKeepsNowAndNextForLaterStationsInsteadOfRejectingTheWholeGuide() {
        val budget = ProgrammeBudget(2, 1000, 8)
        (1..9).forEach { budget.add(Programme("one", "Future $it", 1000L + it * 100, 1100L + it * 100)) }
        budget.add(Programme("two", "Now", 900, 1100))
        budget.add(Programme("one", "Now", 900, 1100))
        budget.add(Programme("two", "Next", 1100, 1200))
        val result = budget.result()
        assertEquals(setOf("one", "two"), result.keys)
        assertTrue(result.values.sumOf { it.size } <= 8)
        assertEquals("Now", result.getValue("one").first().title)
        assertEquals(listOf("Now", "Next"), result.getValue("two").map { it.title })
    }
    @Test fun duplicateStartsReplaceCleanlyAndHistoryWithTheSameEndDoesNotLeakPastTheLimit() {
        val window = ProgrammeBudget.Window(3, 1000)
        (1..20).forEach { window.add(Programme("one", "$it", it.toLong(), 999)) }
        assertEquals(3, window.entries().size)
        window.add(Programme("one", "Current", 900, 1100)); window.add(Programme("one", "Updated", 900, 1150))
        assertEquals(3, window.entries().size); assertEquals("Updated", window.entries().last().title)
    }
    @Test fun normalFeedsRetainTheirHistoryAndUnsortedProgrammes() {
        val budget = ProgrammeBudget(1, 1000, 8)
        budget.add(Programme("one", "Next", 1100, 1200)); budget.add(Programme("one", "Past", 800, 900)); budget.add(Programme("one", "Now", 900, 1100))
        assertEquals(listOf("Past", "Now", "Next"), budget.result().getValue("one").map { it.title })
    }
}
