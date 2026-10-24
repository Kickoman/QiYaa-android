package io.github.kickoman.qiyaa.local

import java.text.Collator
import java.util.Locale

class NaturalOrder(locale: Locale = Locale.getDefault()) : Comparator<String> {
    private val collator = Collator.getInstance(locale)

    override fun compare(a: String, b: String): Int {
        val left = runs(a)
        val right = runs(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val byRun = compareRuns(left[i], right[i])
            if (byRun != 0) return byRun
        }
        if (left.size != right.size) return left.size.compareTo(right.size)
        return a.compareTo(b)
    }

    fun comparePaths(a: List<String>, b: List<String>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val byName = compare(a[i], b[i])
            if (byName != 0) return byName
        }
        return a.size.compareTo(b.size)
    }

    private fun compareRuns(a: String, b: String): Int {
        val aDigits = a[0].isAsciiDigit()
        val bDigits = b[0].isAsciiDigit()
        return when {
            aDigits && bDigits -> compareNumbers(a, b)
            aDigits -> -1
            bDigits -> 1
            else -> collator.compare(a.lowercase(Locale.ROOT), b.lowercase(Locale.ROOT))
        }
    }

    private fun compareNumbers(a: String, b: String): Int {
        val left = a.trimStart('0')
        val right = b.trimStart('0')
        if (left.length != right.length) return left.length.compareTo(right.length)
        return left.compareTo(right)
    }

    private fun runs(text: String): List<String> {
        val runs = ArrayList<String>()
        var start = 0
        for (i in 1..text.length) {
            if (i == text.length || text[i].isAsciiDigit() != text[start].isAsciiDigit()) {
                runs += text.substring(start, i)
                start = i
            }
        }
        return runs
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
