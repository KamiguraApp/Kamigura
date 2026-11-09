package li.mof.kamigura.download

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test

class OfflineImageEpubInventoryTest {
    @Test
    fun auditsRealArchivesOnlyWhenExplicitlyRequested() {
        val root = System.getenv("KAMIGURA_EPUB_AUDIT_ROOT")
        val output = System.getenv("KAMIGURA_EPUB_AUDIT_REPORT")
        assumeTrue("Supply an explicit read-only EPUB inventory root and report path", root != null && output != null)
        val directory = File(root!!)
        val files = directory.walkTopDown().filter { it.isFile && it.extension.equals("epub", true) }.sortedBy { it.path }.toList()
        check(files.isNotEmpty())
        val results = files.map { file ->
            ZipFile(file).use { zip ->
                val entries = try { offlineEpubImageEntries(zip) } catch (_: UnsupportedOfflineFormatException) { null }
                val digest = MessageDigest.getInstance("SHA-256")
                zip.entries().asSequence().forEach { digest.update("${it.name}\u0000${it.size}\u0000${it.crc}\n".toByteArray()) }
                buildJsonObject {
                    put("file", file.relativeTo(directory).invariantSeparatorsPath)
                    put("archiveIndexFingerprint", digest.digest().joinToString("") { "%02x".format(it) })
                    put("status", if (entries != null) "Supported" else "Unsupported")
                    put("images", entries?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonArray(emptyList()))
                }
            }
        }
        File(output!!).writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(results)))
        println("Audited ${files.size} EPUB archives; source files were read only.")
    }
}
