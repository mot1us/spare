package io.github.mot1us.spare

import org.json.JSONObject

/** Supplemental scoreboard state, only when supplied; never inferred from video or a playlist. */
object SportsSituation {
    fun status(timing: JSONObject, type: JSONObject, league: String): String {
        val provided = type.optString("shortDetail").ifBlank { type.optString("detail") }
        if (provided.isNotBlank()) return provided.take(100)
        if (type.optString("state") != "in") return type.optString("description").take(100)
        val period = timing.optInt("period").takeIf { it in 1..30 }
        val clock = timing.optString("displayClock").takeIf { it.matches(Regex("[0-9]{1,3}(:[0-9]{2})?(\\.[0-9])?")) }
        val part = period?.let { when (league) {
            "NFL", "NCAAF", "NBA", "NCAAW" -> "${if (it > 4) "OT ${it - 4}" else "Q$it"}"
            "NCAAM" -> if (it > 2) "OT ${it - 2}" else "Half $it"
            "NHL" -> if (it > 3) "OT ${it - 3}" else "Period $it"
            "EPL", "MLS", "UCL" -> if (it > 2) "Extra time" else "Half $it"
            else -> null
        } }
        return listOfNotNull(part, clock).joinToString(" · ").ifBlank { type.optString("description").take(100) }
    }

    fun detail(value: JSONObject?, league: String): String {
        if (value == null) return ""
        if (league in setOf("NFL", "NCAAF")) {
            // The full text usually already includes field position ("2nd & Goal at ATL 9").
            val full = value.optString("downDistanceText").take(100)
            if (full.isNotBlank()) return full
            return listOf(value.optString("shortDownDistanceText"), value.optString("possessionText"))
                .filter { it.isNotBlank() }.joinToString(" · ").take(120)
        }
        if (league in setOf("MLB", "NCAABSB")) {
            val outs = value.optInt("outs", -1).takeIf { it in 0..3 }
            val balls = value.optInt("balls", -1).takeIf { it in 0..3 }
            val strikes = value.optInt("strikes", -1).takeIf { it in 0..2 }
            return listOfNotNull(outs?.let { "$it ${if (it == 1) "out" else "outs"}" },
                if (balls != null && strikes != null) "$balls–$strikes count" else null).joinToString(" · ")
        }
        return ""
    }
}
