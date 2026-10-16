package com.bitchat.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release builds must not keep verbose logs, and no build may log identity links or panic state. */
class ReleaseLogPrivacyTest {
    private val sources = File("src/main/java")

    @Test
    fun `release R8 configuration strips verbose debug info and warning logs`() {
        val gradle = File("build.gradle.kts").readText()
        val release = gradle.substringAfter("release {").substringBefore("vcsInfo")
        assertTrue("release must minify", release.contains("isMinifyEnabled = true"))
        assertTrue("release must apply proguard-rules.pro", release.contains("\"proguard-rules.pro\""))

        val rules = File("proguard-rules.pro").readText()
        val block = Regex("""-assumenosideeffects\s+class\s+android\.util\.Log\s*\{([^}]*)\}""")
            .find(rules)?.groupValues?.get(1)
        assertTrue("missing -assumenosideeffects rule for android.util.Log", block != null)
        listOf("v", "d", "i", "w").forEach { level ->
            assertTrue("Log.$level is not stripped", Regex("""\b$level\(\.\.\.\)""").containsMatchIn(block!!))
        }
    }

    @Test
    fun `no log call links identities or reveals an emergency wipe`() {
        val forbidden = listOf(
            Regex("panic", RegexOption.IGNORE_CASE),
            Regex("(old|new)\\s*peer\\s*id", RegexOption.IGNORE_CASE),
            Regex("""\$\{?[A-Za-z_.]*nickname""", RegexOption.IGNORE_CASE),
            Regex("""\$\{?message\.(sender|content)\b"""),
            Regex(""":\s*\${'$'}content\b""")
        )
        val offenders = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            logCalls(file.readText()).filter { call -> forbidden.any { it.containsMatchIn(call) } }
                .map { "${file.relativeTo(sources)}: ${it.lineSequence().first().trim()}" }
        }.toList()
        assertTrue("Sensitive log calls:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** Returns the full text of each `Log.x(...)` call, including calls spanning several lines. */
    private fun logCalls(source: String): List<String> {
        val calls = mutableListOf<String>()
        Regex("""\bLog\.[vdiwe]\(""").findAll(source).forEach { match ->
            var depth = 0
            var end = match.range.last
            while (end < source.length) {
                when (source[end]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                end++
            }
            calls += source.substring(match.range.first, minOf(end + 1, source.length))
        }
        return calls
    }
}
