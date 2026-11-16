package com.tgmonitor.app.engine

import java.security.MessageDigest

object ContentHasher {
    fun hash(text: String, links: List<String>): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((text + "\u0000" + links.joinToString("\n")).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

object LinkCodec {
    fun encode(links: List<String>): String = links.joinToString("\n")
    fun decode(s: String): List<String> = if (s.isBlank()) emptyList() else s.split("\n")
}

object ContentFormat {
    const val LINKS_HEADER = "🔗 الروابط:"
    fun display(text: String, links: List<String>): String =
        if (links.isEmpty()) text else text + "\n\n" + LINKS_HEADER + "\n" + links.joinToString("\n")
}

data class EditClassification(val type: String, val details: List<String>)

object EditClassifier {
    fun classify(oldText: String, oldLinks: List<String>, newText: String, newLinks: List<String>): EditClassification {
        val details = mutableListOf<String>()
        val textChanged = oldText != newText
        val linksChanged = oldLinks != newLinks
        if (textChanged) {
            details += when {
                oldText.isNotEmpty() && newText.contains(oldText) -> "TEXT_ADDED"
                newText.isNotEmpty() && oldText.contains(newText) -> "TEXT_REMOVED"
                else -> "TEXT_MODIFIED"
            }
        }
        if (linksChanged) {
            val added = newLinks.filter { it !in oldLinks }
            val removed = oldLinks.filter { it !in newLinks }
            details += when {
                added.isNotEmpty() && removed.isEmpty() -> "LINK_ADDED"
                removed.isNotEmpty() && added.isEmpty() -> "LINK_REMOVED"
                added.isNotEmpty() -> "LINK_MODIFIED"
                else -> "LINK_REORDERED"
            }
        }
        val type = when {
            textChanged && linksChanged -> com.tgmonitor.app.data.ChangeType.TEXT_AND_LINKS_EDITED
            linksChanged -> com.tgmonitor.app.data.ChangeType.LINKS_EDITED
            else -> com.tgmonitor.app.data.ChangeType.TEXT_EDITED
        }
        return EditClassification(type, details)
    }
}

/** Word-level diff (LCS). Whitespace is kept as tokens so spans can be concatenated back. */
enum class DiffType { SAME, ADDED, REMOVED }

data class DiffSpan(val text: String, val type: DiffType)

object WordDiff {
    private const val MAX_CELLS = 4_000_000L
    private val TOKEN = Regex("\\s+|\\S+")

    fun diff(old: String, new: String): List<DiffSpan> {
        if (old == new) return if (old.isEmpty()) emptyList() else listOf(DiffSpan(old, DiffType.SAME))
        val a = TOKEN.findAll(old).map { it.value }.toList()
        val b = TOKEN.findAll(new).map { it.value }.toList()

        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val out = ArrayList<DiffSpan>()
        for (i in 0 until start) out += DiffSpan(a[i], DiffType.SAME)
        val ma = a.subList(start, endA)
        val mb = b.subList(start, endB)
        if (ma.size.toLong() * mb.size.toLong() > MAX_CELLS) {
            if (ma.isNotEmpty()) out += DiffSpan(ma.joinToString(""), DiffType.REMOVED)
            if (mb.isNotEmpty()) out += DiffSpan(mb.joinToString(""), DiffType.ADDED)
        } else {
            val n = ma.size
            val m = mb.size
            val lcs = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                lcs[i][j] = if (ma[i] == mb[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    ma[i] == mb[j] -> { out += DiffSpan(ma[i], DiffType.SAME); i++; j++ }
                    lcs[i + 1][j] >= lcs[i][j + 1] -> { out += DiffSpan(ma[i], DiffType.REMOVED); i++ }
                    else -> { out += DiffSpan(mb[j], DiffType.ADDED); j++ }
                }
            }
            while (i < n) { out += DiffSpan(ma[i], DiffType.REMOVED); i++ }
            while (j < m) { out += DiffSpan(mb[j], DiffType.ADDED); j++ }
        }
        for (i in endA until a.size) out += DiffSpan(a[i], DiffType.SAME)
        return merge(out)
    }

    private fun merge(spans: List<DiffSpan>): List<DiffSpan> {
        val merged = ArrayList<DiffSpan>()
        for (s in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.type == s.type) merged[merged.size - 1] = DiffSpan(last.text + s.text, s.type)
            else merged += s
        }
        return merged
    }
}

object ChannelInputParser {
    private val USERNAME = Regex("^[A-Za-z][A-Za-z0-9_]{4,31}$")
    private val URL = Regex("^(?:www\\.)?(?:t|telegram)\\.me/(?:s/)?([^/?#]+)", RegexOption.IGNORE_CASE)
    private val RESERVED = setOf(
        "joinchat", "addstickers", "addemoji", "addtheme", "addlist", "proxy", "socks", "share", "login",
        "setlanguage", "boost", "giftcode", "invoice", "iv", "bg", "confirmphone", "msg", "contact",
    )

    /** Returns the lower-cased username, or null if the input is not a public channel reference. */
    fun parse(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("@").replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        val candidate = if (s.contains('/') || s.contains('.')) {
            URL.find(s)?.groupValues?.get(1) ?: return null
        } else s
        if (candidate.lowercase() in RESERVED) return null
        return candidate.takeIf { USERNAME.matches(it) }?.lowercase()
    }
}

object IntervalPolicy {
    const val DEFAULT = 15
    const val MIN = 15
    const val MAX = 10_080
    val PRESETS = listOf(15, 30, 45, 60, 120, 360, 720, 1440)

    sealed interface Validation {
        class Valid(val minutes: Int) : Validation
        class Invalid(val reason: String) : Validation
    }

    fun validate(text: String): Validation {
        val v = text.trim().toIntOrNull()
            ?: return Validation.Invalid("أدخل رقماً صحيحاً بالدقائق")
        return when {
            v < MIN -> Validation.Invalid("الحد الأدنى $MIN دقيقة (قيد WorkManager في Android)")
            v > MAX -> Validation.Invalid("الحد الأقصى $MAX دقيقة (أسبوع)")
            else -> Validation.Valid(v)
        }
    }
}
