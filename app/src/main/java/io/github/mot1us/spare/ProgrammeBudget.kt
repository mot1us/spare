package io.github.mot1us.spare

/** Preserve a useful guide for every station when a provider exceeds the memory budget. */
class ProgrammeBudget(private val channels: Int, private val now: Long, private val limit: Int = MAX) {
    init { require(limit in 1..MAX && channels in 0..limit) }
    private val initial = ArrayList<Programme>()
    private var bounded: MutableMap<String, Window>? = null
    private val perChannel get() = (limit / channels.coerceAtLeast(1)).coerceIn(1, 500)
    fun add(programme: Programme) {
        if (bounded == null && initial.size < limit) { initial.add(programme); return }
        if (bounded == null) {
            bounded = linkedMapOf()
            initial.forEach { bounded!!.getOrPut(it.channel) { Window(perChannel, now) }.add(it) }
            initial.clear()
        }
        bounded!!.getOrPut(programme.channel) { Window(perChannel, now) }.add(programme)
    }
    fun result(): Map<String, List<Programme>> = bounded?.mapValues { it.value.entries() }
        ?: initial.groupBy { it.channel }.mapValues { (_, values) -> values.distinctBy { it.start }.sortedBy { it.start } }
    class Window(private val limit: Int, private val now: Long) {
        private fun kind(value: Programme) = if (value.end <= now) 2 else if (value.start <= now) 0 else 1
        private val order = compareBy<Programme> { kind(it) }.thenBy { if (kind(it) == 2) -it.end else it.start }.thenBy { it.start }.thenBy { it.end }
        private val values = java.util.TreeSet(order)
        private val starts = mutableMapOf<Long, Programme>()
        fun add(value: Programme) {
            starts.remove(value.start)?.let(values::remove)
            values.add(value); starts[value.start] = value
            if (values.size > limit) values.pollLast()?.let { starts.remove(it.start) }
        }
        fun entries() = values.sortedBy { it.start }
    }
    companion object { const val MAX = 200000 }
}
