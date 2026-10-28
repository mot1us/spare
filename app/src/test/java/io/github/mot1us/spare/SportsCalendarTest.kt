package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SportsCalendarTest {
    @Test fun localCalendarDayOnlyRequestsTheRelevantEasternScoreboardDates() {
        val day = LocalDate.of(2031, 7, 14)
        assertEquals(listOf(day, day.plusDays(1)), SportsSchedule.calendarDates(day, java.time.ZoneId.of("America/Los_Angeles")))
        assertEquals(listOf(day.minusDays(1), day), SportsSchedule.calendarDates(day, java.time.ZoneId.of("Asia/Tokyo")))
        assertEquals(listOf(day), SportsSchedule.calendarDates(day, java.time.ZoneId.of("America/New_York")))
        val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val result = SportsSchedule.load(emptySet(), requestedDate = day, enabledLeagues = setOf("NFL"), calendarOnly = true, get = { url -> urls.add(url); """{"events":[]}""" })
        assertEquals(SportsSchedule.calendarDates(day).size, urls.size)
        assertTrue(urls.all { "/football/nfl/scoreboard?dates=" in it }); assertEquals(setOf("NFL"), result.leagues)
    }
    @Test fun monthGridContainsEveryDayWithCompleteSundayFirstWeeks() {
        listOf("2026-02", "2024-02", "2026-10", "2026-11", "2026-12").forEach { value ->
            val month = YearMonth.parse(value); val dates = SportsCalendar.days(month)
            assertEquals(42, dates.size); assertEquals(java.time.DayOfWeek.SUNDAY, dates.first().dayOfWeek)
            assertEquals(month.lengthOfMonth(), dates.count { YearMonth.from(it) == month })
            assertEquals(42, dates.distinct().size)
            assertTrue(dates.zipWithNext().all { (a, b) -> a.plusDays(1) == b })
        }
        assertTrue(LocalDate.of(2024, 2, 29) in SportsCalendar.days(YearMonth.of(2024, 2)))
    }
}
