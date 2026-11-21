package com.vinnovateit.latch.core.mp

/** Minimal `Pattern`/`Matcher` over Kotlin's [Regex] for the portal parsers. */
class KPattern private constructor(private val regex: Regex) {
    companion object {
        const val CASE_INSENSITIVE = 0x02
        const val DOTALL = 0x20

        fun compile(pattern: String, flags: Int = 0): KPattern {
            val options = buildSet {
                if (flags and CASE_INSENSITIVE != 0) add(RegexOption.IGNORE_CASE)
                if (flags and DOTALL != 0) add(RegexOption.DOT_MATCHES_ALL)
            }
            return KPattern(Regex(pattern, options))
        }
    }

    fun matcher(input: CharSequence): KMatcher = KMatcher(regex.findAll(input).iterator())
}

class KMatcher(private val matches: Iterator<MatchResult>) {
    private var current: MatchResult? = null
    fun find(): Boolean {
        if (!matches.hasNext()) { current = null; return false }
        current = matches.next()
        return true
    }
    fun group(index: Int): String? = current?.groupValues?.getOrNull(index)
    fun replaceAll(replacement: String): String = current?.value?.replace(current!!.value, replacement) ?: ""
}

class KThreadLocal<T>(private val supplier: () -> T) {
    private val value: T by lazy(supplier)
    fun get(): T = value
}
