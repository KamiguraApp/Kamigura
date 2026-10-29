package li.mof.kamigura

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.reader.novel.*
import li.mof.kamigura.ui.theme.KamiguraTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderSettingsFontsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var view: NovelPageView
    private var position: NovelPosition? = null
    private var reports = 0

    @Test fun settingsSectionsPersistIndependentChoicesAndFitTheWindow() {
        val store = AppSettingsStore(context)
        val original = runBlocking { store.flow.first().reader }
        try {
            runBlocking { store.setReadingDirection(ReaderReadingDirection.LeftToRight) }
            compose.setContent { KamiguraTheme { ReaderSettingsScreen(store, {}) } }
            compose.onNodeWithText("Common").assertIsDisplayed()
            capture("common")
            compose.onNodeWithText("Image reader").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Off")[0].performScrollTo().performClick()
            compose.waitUntil { runBlocking { store.flow.first().reader.image.pageTransition == PageTransition.Off } }
            compose.onNodeWithText("Slide").performScrollTo().performClick()
            compose.waitUntil { runBlocking { store.flow.first().reader.image.pageTransition == PageTransition.Slide } }
            compose.onNodeWithText("Curl").performScrollTo().performClick()
            compose.waitUntil { runBlocking { store.flow.first().reader.image.pageTransition == PageTransition.Curl } }
            compose.onNodeWithText("Page background").assertDoesNotExist()
            compose.onNodeWithText("Writing style").performScrollTo()
            compose.onAllNodesWithText("Vertical").onLast().performScrollTo().performClick()
            compose.onNodeWithText("Dark").performScrollTo().performClick()
            compose.onNodeWithText("Fonts").performScrollTo().assertIsDisplayed()
            capture("novel")
            compose.onNodeWithText("Import Mincho").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Import Gothic").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Font for books without one").performScrollTo()
            compose.onAllNodesWithText("Gothic").onLast().performScrollTo().performClick()
            capture("fonts")
            compose.waitUntil {
                runBlocking { store.flow.first().reader.novel.let {
                    it.background == PageBackground.Dark && it.writingStyle == NovelWritingStyle.Vertical && it.defaultFont == NovelFontSlot.Gothic
                } }
            }
            runBlocking { store.setInvertMode(InvertMode.Off) }
            assertEquals(PageBackground.Dark, runBlocking { store.flow.first().reader.novel.background })
        } finally { runBlocking {
            store.setPageTransition(original.image.pageTransition)
            store.setReadingDirection(original.image.readingDirection)
            store.setInvertMode(original.image.invertMode)
            store.setNovelBackground(original.novel.background)
            store.setNovelWritingStyle(original.novel.writingStyle)
            store.setNovelDefaultFont(original.novel.defaultFont)
        } }
    }

    @Test fun bookWritingDeclarationWinsOverFallbackButNotExplicitChoice() {
        create()
        val verticalBook = "<div class='class2'><style>.class2{writing-mode:vertical-rl}</style><p>Book declaration.</p></div>"
        open(verticalBook, null)
        assertTrue(position!!.vertical)
        js("window.novelReader.reflow()")
        assertEquals("true", js("window.novelReader.state().vertical"))
        open(verticalBook, false)
        assertFalse(position!!.vertical)
        open("<div><p>No declaration.</p></div>", null)
        assertFalse(position!!.vertical)
        open("<div><p>No declaration.</p></div>", null, NovelReaderPreferences(writingStyle = 1))
        assertTrue(position!!.vertical)
        open("<div style='writing-mode:horizontal-tb'><p>Explicit book horizontal.</p></div>", null, NovelReaderPreferences(writingStyle = 1))
        assertFalse(position!!.vertical)
        open("<div><style>.book-content{writing-mode:vertical-rl}</style><p>Scoped body declaration.</p></div>", null)
        assertTrue(position!!.vertical)
        open("<div><style>@media (min-width:99999px){.book-content{writing-mode:horizontal-tb}}</style><p>Inactive rule.</p></div>", null,
            NovelReaderPreferences(writingStyle = 1))
        assertTrue(position!!.vertical)
        compose.runOnIdle { view.dispose() }
    }

    @Test fun importedFontsDecodeRespectEmbeddedFacesAndReflowWithoutLosingTheCharacter() {
        val fonts = NovelFontStore(context)
        val mincho = runBlocking { fonts.import(Uri.fromFile(File("/system/fonts/Roboto-Regular.ttf"))) }
        var gothic: NovelFont? = null
        try {
            val importedGothic = runBlocking { fonts.import(Uri.fromFile(File("/system/fonts/NotoSerifCJK-Regular.ttc"))) }
            gothic = importedGothic
            assertNotEquals(mincho.id, importedGothic.id)
            val config = NovelSettings(mincho = mincho, gothic = importedGothic)
            assertNull(fonts.response(NovelFontOrigin + mincho.id, emptySet()))
            assertNull(fonts.response("https://example.invalid/novel-fonts/${mincho.id}", setOf(mincho.id!!)))
            val response = fonts.response(NovelFontOrigin + mincho.id, setOf(mincho.id!!))!!
            assertTrue(response.data.use { it.readBytes().contentEquals(File("/system/fonts/Roboto-Regular.ttf").readBytes()) })
            ReaderServer(3, bookFont = File("/system/fonts/Roboto-Regular.ttf").readBytes()).use { server ->
                create()
                val html = "<div><style>@font-face{font-family:EmbeddedBook;src:url('${server.baseUrl}/api/Book/1/book-resources?font=embedded')}" +
                    ".serif{font-family:serif}.sans{font-family:sans-serif}.embedded{font-family:EmbeddedBook,serif}</style>" +
                    "<p class='serif'>Serif sample</p><p class='sans'>Sans sample</p><p class='embedded'>Embedded sample</p>" +
                    "<p id='default'>Default sample</p>" + (1..120).joinToString("") { "<p id='p$it'>${"Independent paragraph content for font reflow. ".repeat(5)}</p>" } + "</div>"
                open(html, false, NovelReaderPreferences(fonts = config), server.baseUrl)
                assertTrue(js("getComputedStyle(document.querySelector('.serif')).fontFamily").contains("KamiguraMincho"))
                assertTrue(js("getComputedStyle(document.querySelector('.sans')).fontFamily").contains("KamiguraGothic"))
                assertTrue(js("getComputedStyle(document.querySelector('.embedded')).fontFamily").indexOf("EmbeddedBook") <
                    js("getComputedStyle(document.querySelector('.embedded')).fontFamily").indexOf("KamiguraMincho"))
                assertEquals("true", js("Array.from(document.fonts).some(f=>f.family==='EmbeddedBook'&&f.status==='loaded')"))
                assertEquals("true", js("Array.from(document.fonts).some(f=>f.family==='KamiguraMincho'&&f.status==='loaded')"))
                val embeddedWidthScript = "(()=>{const r=document.createRange();r.selectNodeContents(document.querySelector('.embedded'));return r.getBoundingClientRect().width})()"
                val embeddedWidth = js(embeddedWidthScript).toDouble()
                repeat(4) { val previous = reports; compose.runOnIdle { view.turn(1) { fail("Early boundary") } }; compose.waitUntil { reports > previous } }
                val locator = position!!.location
                val previous = reports
                compose.runOnIdle { view.setFonts(config.copy(defaultFont = NovelFontSlot.Gothic, mincho = importedGothic, gothic = mincho)) }
                compose.waitUntil(20_000) { reports > previous }
                assertEquals(embeddedWidth, js(embeddedWidthScript).toDouble(), 0.01)
                assertEquals(locator, position!!.location)
                assertEquals("true", js("(()=>{const p=JSON.parse(${JSONObject.quote(locator)}),w=document.createTreeWalker(document.querySelector('.book-content').firstElementChild,NodeFilter.SHOW_TEXT),a=[];while(w.nextNode())if(w.currentNode.textContent.trim()&&!w.currentNode.parentElement.closest('style,script,rt,rp,.visually-hidden'))a.push(w.currentNode);const r=document.createRange();r.setStart(a[p.text],p.offset);r.setEnd(a[p.text],p.offset+1);return Array.from(r.getClientRects()).some(b=>b.right>0&&b.left<innerWidth&&b.bottom>0&&b.top<innerHeight)})()"))
                assertTrue(js("getComputedStyle(document.getElementById('default')).fontFamily").contains("KamiguraGothic"))
                val reset = reports
                compose.runOnIdle { view.setFonts(NovelSettings()) }
                compose.waitUntil(20_000) { reports > reset }
                assertEquals(locator, position!!.location)
                assertEquals("0", js("document.getElementById('novel-slot-fonts').sheet.cssRules.length"))
                compose.runOnIdle { view.dispose() }
            }
        } finally { fonts.remove(mincho); gothic?.let(fonts::remove) }
    }

    @Test fun invalidFontIsRejectedWithoutReplacingEitherSlot() {
        val file = File(context.cacheDir, "invalid-font.ttf").apply { writeText("Not a font") }
        val fonts = NovelFontStore(context)
        try {
            try { runBlocking { fonts.import(Uri.fromFile(file)) }; fail("Invalid font accepted") }
            catch (expected: Exception) { assertTrue(expected.message.orEmpty().contains("font", ignoreCase = true)) }
        } finally { file.delete() }
    }

    @Test fun fontSlotsPersistIndependentlyAndResetOnlyTheSelectedSlot() {
        val store = AppSettingsStore(context)
        val original = runBlocking { store.flow.first().reader.novel }
        val fonts = NovelFontStore(context)
        val first = runBlocking { fonts.import(Uri.fromFile(File("/system/fonts/Roboto-Regular.ttf"))) }.copy(name = "Mincho test.ttf")
        val second = runBlocking { fonts.import(Uri.fromFile(File("/system/fonts/Roboto-Regular.ttf"))) }.copy(name = "Gothic test.ttf")
        try {
            runBlocking { store.setNovelFont(NovelFontSlot.Mincho, first); store.setNovelFont(NovelFontSlot.Gothic, second) }
            val persisted = runBlocking { AppSettingsStore(context).flow.first().reader.novel }
            assertEquals(first, persisted.mincho); assertEquals(second, persisted.gothic)
            compose.setContent { KamiguraTheme { ReaderSettingsScreen(store, {}) } }
            compose.onNodeWithText("Mincho test.ttf").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Reset to default")[0].performScrollTo().performClick()
            compose.waitUntil { runBlocking { store.flow.first().reader.novel.mincho.id == null } }
            assertEquals(second, runBlocking { store.flow.first().reader.novel.gothic })
            compose.onNodeWithText("Noto Serif CJK JP (system)").performScrollTo().assertIsDisplayed()
            assertNull(fonts.response(NovelFontOrigin + first.id, setOf(first.id!!)))
            fonts.response(NovelFontOrigin + second.id, setOf(second.id!!))!!.data.close()
        } finally {
            runBlocking { store.setNovelFont(NovelFontSlot.Mincho, original.mincho); store.setNovelFont(NovelFontSlot.Gothic, original.gothic) }
            fonts.remove(first); fonts.remove(second)
        }
    }

    @Test fun suppliedOtfFixtureLoadsInTheInstalledWebView() {
        val path = InstrumentationRegistry.getArguments().getString("novelOtfFixture")
        org.junit.Assume.assumeTrue("Supply a local OTF fixture with novelOtfFixture", path != null)
        val fonts = NovelFontStore(context)
        val font = runBlocking { fonts.import(Uri.fromFile(File(path!!))) }
        try { assertTrue(font.id!!.endsWith(".otf")) }
        finally { fonts.remove(font) }
    }

    private fun create() {
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { value ->
            view = value; value.onPosition = { position = it; reports++ }
        } }, modifier = Modifier.requiredSize(360.dp, 620.dp)) }
    }
    private fun open(html: String, vertical: Boolean?, preferences: NovelReaderPreferences = NovelReaderPreferences(), base: String = "http://127.0.0.1") {
        position = null
        compose.runOnIdle { view.open(html, base, 1, null, false, vertical, preferences = preferences) }
        compose.waitUntil(20_000) { position != null }
    }
    private fun js(script: String): String {
        val latch = CountDownLatch(1); var value = ""
        compose.runOnIdle { view.evaluateJavascript(script) { value = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS)); return value
    }
    private fun capture(section: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val shape = if (bitmap.width > bitmap.height) "tablet" else "phone"
        File(context.cacheDir, "v029-settings-$section-$shape.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
