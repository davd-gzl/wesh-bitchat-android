package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LanguageCatalogContractTest {
    private val resourcesDirectory = File("src/main/res")

    @Test
    fun `locale config exposes exactly English and French`() {
        val shippedLanguageTags = resourcesDirectory
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .mapNotNull { directory -> directory.name.toLanguageTagOrNull() }
            .toSet()

        val configuredLanguageTags = Regex("""android:name="([^"]+)"""")
            .findAll(File(resourcesDirectory, "xml/locales_config.xml").readText())
            .map { match -> match.groupValues[1] }
            .toList()

        assertEquals(listOf("en", "fr"), configuredLanguageTags)
        assertEquals(setOf("fr"), shippedLanguageTags)
    }

    @Test
    fun `language picker strings exist in every locale pack`() {
        val requiredKeys = setOf(
            "about_language",
            "about_app_language",
            "about_system_default",
            "about_select_language",
        )

        resourcesDirectory
            .listFiles()
            .orEmpty()
            .filter { directory ->
                directory.isDirectory &&
                    (directory.name == "values" || directory.name.toLanguageTagOrNull() != null)
            }
            .forEach { directory ->
                val strings = File(directory, "strings.xml").readText()
                requiredKeys.forEach { key ->
                    assertTrue(
                        "$key is missing from ${directory.name}/strings.xml",
                        """name="$key""" in strings,
                    )
                }
            }
    }

    @Test
    fun `translation errors are neither suppressed nor allowed to pass lint`() {
        val missingTranslationSuppressions = resourcesDirectory
            .walkTopDown()
            .filter(File::isFile)
            .filter { it.extension == "xml" }
            .filter { "MissingTranslation" in it.readText() }
            .map { it.relativeTo(resourcesDirectory).path }
            .toList()

        assertTrue(
            "MissingTranslation must be fixed within the EN/FR product scope, not suppressed: $missingTranslationSuppressions",
            missingTranslationSuppressions.isEmpty(),
        )
        assertTrue(
            "Lint errors must fail the build",
            "abortOnError = true" in File("build.gradle.kts").readText(),
        )
    }

    private fun String.toLanguageTagOrNull(): String? {
        if (!startsWith("values-") || this == "values-night") return null
        return removePrefix("values-")
            .split("-")
            .joinToString("-") { qualifier ->
                if (qualifier.startsWith("r") && qualifier.length == 3) {
                    qualifier.drop(1)
                } else {
                    qualifier
                }
            }
    }
}
