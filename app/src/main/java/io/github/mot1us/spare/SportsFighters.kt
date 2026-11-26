package io.github.mot1us.spare

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * A fighter stands in for a team on a fight night's card. The id carries the fighter's name, so a schedule saved on this TV
 * shows the right names after a restart without a fighter table.
 */
object SportsFighters {
    private const val MARK = ":fighter:"
    private val number = Regex("[0-9]{1,20}")
    private val known = java.util.concurrent.ConcurrentHashMap<String, SportsTeam>()
    fun id(league: String, apiId: String, name: String): String? {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(80)
        if (clean.isEmpty() || !apiId.matches(number) || !SportsLeagues.fights(league)) return null
        return league.lowercase(Locale.ROOT) + MARK + apiId + ":" + URLEncoder.encode(clean, "UTF-8")
    }
    fun team(id: String): SportsTeam? {
        known[id]?.let { return it }
        val at = id.indexOf(MARK); if (at <= 0 || id.length > 400) return null
        val league = id.substring(0, at).uppercase(Locale.ROOT); if (!SportsLeagues.fights(league)) return null
        val rest = id.substring(at + MARK.length); val apiId = rest.substringBefore(':')
        if (!apiId.matches(number)) return null
        val name = runCatching { URLDecoder.decode(rest.substringAfter(':', ""), "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A surname finds the fight in a listing ("UFC Fight Night: Adesanya vs. Magomedov"); a first name alone would not.
        val surname = name.substringAfterLast(' ').takeIf { it.length >= 3 && it != name }
        return SportsTeam(id, league, name, listOfNotNull(name, surname), apiId).also { if (known.size < 2048) known[id] = it }
    }
}
