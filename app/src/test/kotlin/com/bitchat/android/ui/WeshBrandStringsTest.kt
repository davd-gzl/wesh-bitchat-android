package com.bitchat.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WeshBrandStringsTest {
    private val res = File("src/main/res")

    private fun values(directory: String): Map<String, String> = res.resolve(directory).listFiles().orEmpty()
        .filter { it.name.startsWith("strings") && it.extension == "xml" }
        .flatMap { file ->
            Regex("""<(?:string|item)\b[^>]*?(?:name="([^"]+)")?[^>]*>(.*?)</(?:string|item)>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText()).map { (it.groupValues[1].ifEmpty { file.name }) to it.groupValues[2] }
        }.toMap()

    @Test
    fun `no shipped locale shows the BitChat brand`() {
        val offenders = res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values") }
            .flatMap { dir -> values(dir.name).filterValues { it.contains("bitchat", ignoreCase = true) }.keys.map { "${dir.name}/$it" } }
        assertTrue("BitChat brand in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `French strings address the person with vous`() {
        val informal = Regex("""(^|[\s>(«“])(tu|ton|ta|tes|toi|t’|t')(?=[\s,.!?’'])""", RegexOption.IGNORE_CASE)
        val offenders = values("values-fr").filterValues { informal.containsMatchIn(it) }.keys
        assertTrue("Informal French in: $offenders", offenders.isEmpty())
    }
}
