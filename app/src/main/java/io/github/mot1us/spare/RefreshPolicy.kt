package io.github.mot1us.spare

/** Independent schedules; zero means manual. Launch refresh never blocks the saved guide. */
data class RefreshPolicy(val playlistHours: Int = 0, val epgHours: Int = 0, val playlistOnLaunch: Boolean = false, val epgOnLaunch: Boolean = false) {
    fun playlistDue(last: Long, now: Long, launch: Boolean = false) = due(playlistHours, last, now) || launch && playlistOnLaunch
    fun epgDue(last: Long, now: Long, launch: Boolean = false) = due(epgHours, last, now) || launch && epgOnLaunch
    private fun due(hours: Int, last: Long, now: Long) = hours > 0 && (last <= 0 || now < last || now - last >= hours * 3600000L)
    companion object { val intervals = listOf(0, 1, 3, 6, 12, 24, 48); fun label(hours: Int) = if (hours == 0) "Manual" else "Every $hours hours" }
}
