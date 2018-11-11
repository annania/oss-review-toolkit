import java.io.File
import java.nio.charset.Charset

val FILE = "/home/frank/copyright.txt"

data class CopyrightEntry(val original: String, val years: Set<Int>, val copyright: String, val payload: String) {

}

data class MergedEntry(val original: String, val years: Set<Int>, val copyright: String, val payload: String, var entries: MutableList<CopyrightEntry> = mutableListOf()) {

}

fun main(args: Array<String>) {

    val lines = getLines(FILE)
    val entries = mutableListOf<CopyrightEntry>()
    lines.forEach { line ->
        val a = extractCopyrigthStatement(line)
        var b = extractYears(a.first)
        val entry = CopyrightEntry(
                original = line,
                years = b.second,
                copyright = a.second,
                payload = b.first.replace("([,| ]*)^[,| |_]*".toRegex(), "")
                        .trim()
        )
        entries.add(entry)
    }

    val entryMap = mutableMapOf<String, MutableList<CopyrightEntry>>()
    entries.forEach {
        val key = normalizeKey(it.payload)
        if (!entryMap.containsKey(key)) {
            entryMap[key] = mutableListOf()
        }
        entryMap[key]!!.add(it)
    }

    val mergedEntries = mutableListOf<MergedEntry>()
    entryMap.entries.forEach {
        val years = mutableListOf<Int>()
        it.value.forEach {
            years.addAll(it.years)
        }
        val entry = MergedEntry(
                original = "",
                years = years.toSet(),
                copyright = it.value.first().copyright,
                payload = it.value.first().payload,
                entries = it.value
        )
        mergedEntries.add(entry)
    }

    val sorted = mergedEntries.sortedWith(compareBy({it.years.isEmpty()}, { it.payload }, { prettyPrintYears(it.years) } ))
    sorted.forEach { it ->
        val c = if (it.copyright.isEmpty()) "" else "Copyright (c)"
        val str = (c + " " + prettyPrintYears(it.years) + " " + it.payload).replace("  ", " ")
        println(str)
        it.entries.forEach { entry ->
            println("    " + entry.original)
        }
    }
    println(extractYears("\n" +
            "Copyright (c) 2002,2003,2004,2005,2006,2007 Atsuhiko Yamanaka"))
}


fun prettyPrintYears(years: Collection<Int>) =
        getYearRanges(years).joinToString (separator = ", ") {
            if (it.first == it.second) it.first.toString()
            else "${it.first}-${it.second}"
        }

fun extractCopyrigthStatement(copyright: String): Pair<String, String> {
    "(?=.*)([C|c]opyrighted \\(c\\))".toRegex().findAll(copyright).forEach { matchResult ->
        return Pair(copyright.replace(matchResult.groups[1]!!.value, ""), matchResult.groups[1]!!.value)
    }
    "(?=.*)([C|c]opyright \\(c\\))".toRegex().findAll(copyright).forEach { matchResult ->
        return Pair(copyright.replace(matchResult.groups[1]!!.value, ""), matchResult.groups[1]!!.value)
    }
    "(?=.*)([C|c]opyrighted)".toRegex().findAll(copyright).forEach { matchResult ->
        return Pair(copyright.replace(matchResult.groups[1]!!.value, ""), matchResult.groups[1]!!.value)
    }
    "(?=.*)([C|c]opyright)".toRegex().findAll(copyright).forEach { matchResult ->
        return Pair(copyright.replace(matchResult.groups[1]!!.value, ""), matchResult.groups[1]!!.value)
    }
    "(?=.*)(\\(c\\))".toRegex().findAll(copyright).forEach { matchResult ->
        return Pair(copyright.replace(matchResult.groups[1]!!.value, ""), matchResult.groups[1]!!.value)
    }

    return Pair(copyright, "")
}

fun extractYears(copyright: String): Pair<String, Set<Int>> {
    val result = mutableListOf<Int>()

    val yearRangeRegex = "(?=.*)\\b([\\d]{4})(?:[ ]*[-][ ]*)([\\d]{4}|[\\d]{2}|[\\d]{1})\\b".toRegex()
    val yearRanges = mutableListOf<Pair<Int, Int>>()
    val removeList = mutableListOf<String>()
    yearRangeRegex.findAll(copyright).forEach { matchResult ->
        val fromYear = matchResult.groups[1]!!.value
        val toYear = matchResult.groups[2]!!.value.let { fromYearRaw ->
            "${fromYear.substring(0, fromYear.length - fromYearRaw.length)}$fromYearRaw"
        }

        val range = Pair(fromYear.toInt(), toYear.toInt())
        if (range.first <= range.second) {
            yearRanges.add(range)
        } else {
            println("discardRange: " + range)
        }
        removeList.add(matchResult.groups[0]!!.value)
    }

    yearRanges.forEach{
        result.addAll((it.first..it.second))
    }

    val singleYearsRegex = "(?=.*)\\b([\\d]{4})\\b".toRegex()
    singleYearsRegex.findAll(copyright).forEach { matchResult->
        val year = matchResult.groups[1]!!.value.toInt()
        removeList.add(matchResult.groups[0]!!.value)
        result.add(year)
    }
    val c = copyright.replace(yearRangeRegex, "").replace(singleYearsRegex, "")

    return Pair(c, result.toSortedSet())
}

fun getYearRanges(years: Collection<Int>): List<Pair<Int, Int>> {
    val result = mutableListOf<Pair<Int, Int>>()

    val set = years.toMutableSet()
    years.toSortedSet().forEach { fromYear ->
        if (!set.contains(fromYear)) {
            return@forEach
        }
        set.remove(fromYear)
        var toYear = fromYear
        while (set.contains(toYear + 1)) {
            toYear++
            set.remove(toYear)
        }
        result.add(Pair(fromYear, toYear))
    }

    return result
}

fun normalizeKey(a: String, chars: CharArray = charArrayOf('<', '>', ' ', '(', ')', '[', ']', ';', '.', ',', '-', '~')): String {
    var x = a;
    chars.forEach {
        x = x.replace("$it", "")
    }
    return x
}


fun getLines(file: String): List<String> =
        File(file).readLines(Charset.defaultCharset()).mapNotNull {
            it.takeIf { it.isNotEmpty() }
        }
