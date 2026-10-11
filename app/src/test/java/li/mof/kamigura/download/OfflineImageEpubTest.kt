package li.mof.kamigura.download

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineImageEpubTest {
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
        block: (ZipFile) -> Unit
    ) {
        val file = Files.createTempFile("image-epub", ".epub").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
                entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/content.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
                val manifest = pages.mapIndexed { index, page ->
                    "<item id='p$index' href='${page.first}' media-type='application/xhtml+xml'/>"
                }.joinToString("")
                val spine = pages.indices.joinToString("") { "<itemref idref='p$it'/>" }
                entry("OPS/content.opf", transformOpf("<package><manifest>$manifest</manifest><spine>$spine</spine></package>"))
                pages.forEach { (path, body) ->
                    entry("OPS/$path", "$prefix<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Ignored metadata</title><style>body { color: black; }</style></head><body>$body</body></html>")
                }
                images.forEach { entry("OPS/$it", "image") }
            }
            ZipFile(file).use(block)
        } finally {
            file.delete()
        }
    }
}
