package li.mof.kamigura.download

import java.nio.file.Files
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineImageEpubTest {
    @Test
    fun bareHtmlDoctypeWithCommercialSvgAndHeadScriptKeepsEverySpineItem() {
        val page = "<div><svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' viewBox='0 0 1440 2048'><image width='1440' height='2048' xlink:href='../images/z.jpg'/></svg></div>"
        for (count in listOf(2, 185, 199)) {
            withEpub((0 until count).map { "pages/${count - it}.xhtml" to page }, listOf("images/z.jpg"),
                prefix = "<?xml version='1.0' encoding='UTF-8'?>\n<!DOCTYPE html>",
                head = "<script src='../../js/kobo.js'/>") {
                assertEquals(List(count) { "OPS/images/z.jpg" }, offlineEpubImageEntries(it))
            }
        }
    }

    @Test
    fun bareDoctypeAllowsXmlWhitespaceCommentsAndProcessingInstructions() {
        for (prefix in listOf("<!DOCTYPE\nhtml \t>",
            "\uFEFF<?xml version='1.0'?><!-- <!DOCTYPE html [<!ENTITY fake 'x'>]> --><?test data?><!DOCTYPE html>")) {
            withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"), prefix = prefix) {
                assertEquals(listOf("OPS/image.jpg"), offlineEpubImageEntries(it))
            }
        }
    }

    @Test
    fun bareDoctypeWorksInUtf16WithoutWeakeningSubsetRejection() {
        for (encoding in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            for (bom in listOf("", "\uFEFF")) {
                val declaration = "$bom<?xml version='1.0' encoding='${encoding.name()}'?>"
                withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"),
                    prefix = "$declaration<!DOCTYPE html>", encoding = encoding) {
                    assertEquals(listOf("OPS/image.jpg"), offlineEpubImageEntries(it))
                }
                withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"),
                    prefix = "$declaration<!-- <!DOCTYPE html> --><!DOCTYPE html [<!ENTITY unused 'x'>]>", encoding = encoding) {
                    expectUnsupported { offlineEpubImageEntries(it) }
                }
            }
        }
    }

    @Test
    fun rejectsAllSubsetsAndExternalDoctypesEvenWithoutEntityReferences() {
        for (prefix in listOf(
            "<!DOCTYPE html []>",
            "<!DOCTYPE html [<!-- harmless-looking subset -->]>",
            "<!DOCTYPE html [<!ENTITY unused 'text'>]>",
            "<!DOCTYPE html [<!ENTITY % remote SYSTEM 'http://127.0.0.1:9/remote.dtd'>%remote;]>",
            "<!DOCTYPE html [<!ENTITY file SYSTEM 'file:///nonexistent'>]>",
            "<!DOCTYPE html SYSTEM 'inside.dtd'>",
            "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://127.0.0.1:9/remote.dtd'>",
            "<!-- <!DOCTYPE html> --><?fake data?><!DOCTYPE html [<!ENTITY unused 'x'>]>",
            "<?fake data='<!DOCTYPE html>'?><!DOCTYPE html [<!ENTITY unused 'x'>]>",
            "<!DOCTYPE html><!DOCTYPE html [<!ENTITY unused 'x'>]>"
        )) {
            withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"), prefix = prefix) {
                expectUnsupported { offlineEpubImageEntries(it) }
            }
        }
    }

    @Test
    fun bareDoctypeDoesNotPermitBodyScriptsOrNovelProse() {
        for (body in listOf("<script src='kobo.js'/><img src='image.jpg'/>", "<p>Novel prose</p>")) {
            withEpub(listOf("page.xhtml" to body), listOf("image.jpg"), prefix = "<!DOCTYPE html>") {
                expectUnsupported { offlineEpubImageEntries(it) }
            }
        }
    }

    @Test
    fun metadataDoctypesRemainUnsupported() {
        withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"),
            transformOpf = { "<!DOCTYPE html>$it" }) { expectUnsupported { offlineEpubImageEntries(it) } }
    }

    @Test
    fun doctypeGateFailsClosedForOtherEncodingsWithoutChangingLegacyXml() {
        val declaration = "<?xml version='1.0' encoding='ISO-8859-1'?>"
        withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"),
            prefix = declaration, encoding = Charsets.ISO_8859_1, head = "<title>caf\u00e9</title>") {
            assertEquals(listOf("OPS/image.jpg"), offlineEpubImageEntries(it))
        }
        for (encoding in listOf("ISO-8859-1", "ISO-2022-JP")) {
            withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"),
                prefix = "<?xml version='1.0' encoding='$encoding'?><!DOCTYPE html>") {
                expectUnsupported { offlineEpubImageEntries(it) }
            }
        }
    }

    @Test
    fun spineOrderIncludesSvgCoverAndRepeatedImageWithoutFilenameSorting() {
        withEpub(
            pages = listOf(
                "titlepage.xhtml" to "<svg xmlns='http://www.w3.org/2000/svg' xmlns:link='http://www.w3.org/1999/xlink'><image link:href='images/z.jpg'/></svg>",
                "pages/10.xhtml" to "<img src='../images/a.jpg'/>",
                "pages/2.xhtml" to "<img src='../images/a.jpg'/>"
            ), images = listOf("images/z.jpg", "images/a.jpg")
        ) { assertEquals(listOf("OPS/images/z.jpg", "OPS/images/a.jpg", "OPS/images/a.jpg"), offlineEpubImageEntries(it)) }
    }

    @Test
    fun resolvesNestedOpfAndPercentEncodedImagePathsAndIgnoresHeadMetadata() {
        withEpub(listOf("pages/p.xhtml" to "<img src='../images/page%201.jpg'/>"), listOf("images/page 1.jpg")) {
            assertEquals(listOf("OPS/images/page 1.jpg"), offlineEpubImageEntries(it))
        }
        assertEquals("OPS/image.jpg", resolveEpubEntry("OPS/pages/p.xhtml", "../image.jpg"))
    }

    @Test
    fun imageOnlyCoverCannotMakeNovelSupported() {
        assertUnsupported(listOf("cover.xhtml" to "<img src='image.jpg'/>", "novel.xhtml" to "<p>Novel prose</p>"))
    }

    @Test
    fun doesNotSkipEmptyMultiImageMissingOrVectorPages() {
        for (body in listOf("", "<img src='missing.jpg'/>", "<img src='image.jpg'/><img src='image.jpg'/>",
            "<svg xmlns='http://www.w3.org/2000/svg'><rect/><image href='image.jpg'/></svg>",
            "<svg xmlns='http://www.w3.org/2000/svg'><text>Caption</text><image href='image.jpg'/></svg>")) {
            assertUnsupported(listOf("page.xhtml" to body))
        }
    }

    @Test
    fun rejectsActiveContentProseAndUnsupportedSvgTransforms() {
        for (body in listOf("<script>run()</script><img src='image.jpg'/>", "<p>Caption<img src='image.jpg'/></p>",
            "<svg xmlns='http://www.w3.org/2000/svg'><g transform='rotate(30)'><image href='image.jpg'/></g></svg>")) {
            assertUnsupported(listOf("page.xhtml" to body))
        }
    }

    @Test
    fun archiveUrisCannotEscapeRootOrReferToNetworkOrFiles() {
        for (source in listOf("../../outside.jpg", "%2e%2e/%2e%2e/outside.jpg", "file:///tmp/image.jpg",
            "https://example.com/image.jpg", "//example.com/image.jpg", "/image.jpg", "image.jpg?x=1",
            "image.jpg#fragment", "..%5coutside.jpg", "%00.jpg")) {
            expectUnsupported { resolveEpubEntry("OPS/page.xhtml", source) }
        }
    }

    @Test
    fun rejectsDoctypeBeforeEntityExpansion() {
        withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"),
            prefix = "<!DOCTYPE html [<!ENTITY external SYSTEM 'file:///nonexistent'>]>") {
            expectUnsupported { offlineEpubImageEntries(it) }
        }
    }

    @Test
    fun boundsXmlAndRejectsXmlBaseRatherThanResolvingWrongImage() {
        assertUnsupported(listOf("page.xhtml" to "<div xml:base='other/'><img src='image.jpg'/></div>"))
        assertUnsupported(listOf("page.xhtml" to "<style>${" ".repeat(2 * 1024 * 1024)}</style><img src='image.jpg'/>"))
    }

    @Test
    fun keepsNonLinearSpineItemsAndRejectsMissingOrDuplicateManifestIds() {
        withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"),
            transformOpf = { it.replace("idref='p0'", "idref='p0' linear='no'") }) {
            assertEquals(listOf("OPS/image.jpg"), offlineEpubImageEntries(it))
        }
        for (transform in listOf<(String) -> String>(
            { it.replace("idref='p0'", "idref='missing'") },
            { it.replace("</manifest>", "<item id='p0' href='page.xhtml' media-type='application/xhtml+xml'/></manifest>") }
        )) {
            withEpub(listOf("page.xhtml" to "<img src='image.jpg'/>"), listOf("image.jpg"), transformOpf = transform) {
                expectUnsupported { offlineEpubImageEntries(it) }
            }
        }
    }

    private fun assertUnsupported(pages: List<Pair<String, String>>) {
        withEpub(pages, listOf("image.jpg")) { expectUnsupported { offlineEpubImageEntries(it) } }
    }

    private fun expectUnsupported(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected unsupported EPUB")
        } catch (error: UnsupportedOfflineFormatException) {
            assertEquals(UnsupportedOfflineEpubMessage, error.message)
        }
    }

    private fun withEpub(
        pages: List<Pair<String, String>>,
        images: List<String>,
        prefix: String = "",
        transformOpf: (String) -> String = { it },
        head: String = "<title>Ignored metadata</title><style>body { color: black; }</style>",
        encoding: Charset = Charsets.UTF_8,
        block: (ZipFile) -> Unit
    ) {
        val file = Files.createTempFile("image-epub", ".epub").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, content: String, charset: Charset = Charsets.UTF_8) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(charset))
                    zip.closeEntry()
                }
                entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/content.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
                val manifest = pages.mapIndexed { index, page ->
                    "<item id='p$index' href='${page.first}' media-type='application/xhtml+xml'/>"
                }.joinToString("")
                val spine = pages.indices.joinToString("") { "<itemref idref='p$it'/>" }
                entry("OPS/content.opf", transformOpf("<package><manifest>$manifest</manifest><spine>$spine</spine></package>"))
                pages.forEach { (path, body) ->
                    entry("OPS/$path", "$prefix<html xmlns='http://www.w3.org/1999/xhtml'><head>$head</head><body>$body</body></html>", encoding)
                }
                images.forEach { entry("OPS/$it", "image") }
            }
            ZipFile(file).use(block)
        } finally {
            file.delete()
        }
    }
}
