package com.bitchat.android.ui.pave

import com.bitchat.android.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Reads the shipped `strings*.xml` files, so JVM tests see the real FR and EN labels. */
internal object PaveStringResources {
    private val resourcesDirectory = File("src/main/res")

    /** `values` is English; `values-fr` French; `values-en` is read too if it ever appears. */
    val localeDirectories: List<String> = listOf("values", "values-en", "values-fr")
        .filter { File(resourcesDirectory, it).isDirectory }

    /** Name to text of every plain `<string>` in [directory]. */
    fun strings(directory: String): Map<String, String> =
        File(resourcesDirectory, directory).listFiles { file -> file.name.matches(Regex("strings.*\\.xml")) }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = document.getElementsByTagName("string")
                (0 until nodes.length).map { index ->
                    val element = nodes.item(index) as Element
                    element.getAttribute("name") to element.textContent.replace("\\'", "'")
                }
            }
            .toMap()

    fun name(id: Int): String =
        R.string::class.java.fields.first { it.getInt(null) == id }.name

    /** Every reserved label as shipped, in every locale directory. */
    fun reservedWords(): Set<String> =
        PaveReservedWords.words(localeDirectories.map(::strings)) { getValue(name(it)) }

    /** The shipped [RemoteNameRule] on a phone set to [directory]'s language, for JVM tests. */
    fun remoteNameRule(directory: String = "values-fr"): RemoteNameRule {
        val labels = strings(directory)
        val reserved = reservedWords()
        return RemoteNameRule { raw -> remoteDisplayName(raw, { labels.getValue(name(it)) }, reserved) }
    }
}
