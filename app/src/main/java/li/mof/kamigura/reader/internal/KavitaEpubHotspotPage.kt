package li.mof.kamigura.reader.internal

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/**
 * Only the balanced, XHTML-like fragment emitted for linked contents is accepted here.
 * Do not let a forgiving HTML parser repair the SVG or conceal a paintable child.
 */
internal fun isKavitaEpubHotspotPage(html: String): Boolean {
    if (html.length > 2 * 1024 * 1024) return false
    val stack = mutableListOf<String>()
    var images = 0
    var hasHotspot = false
    var rectsInLink = 0
    val css = StringBuilder()
    val handler = object : DefaultHandler2() {
        override fun startElement(uri: String, name: String, qName: String, attributes: Attributes) {
            val parent = stack.lastOrNull()
            requireHotspotMarkup(stack.size < 64 && qName == name)
            val values = (0 until attributes.length).associate { index ->
                val key = attributes.getQName(index)
                requireHotspotMarkup(attributes.getURI(index).isEmpty() ||
                    (key == "xlink:href" && attributes.getURI(index) == EpubXlinkNamespace))
                key to attributes.getValue(index)
            }
            requireHotspotMarkup(values["style"]?.let(::isLayoutOnlyEpubStyle) != false)
            when (name) {
                "fragment" -> requireHotspotMarkup(parent == null)
                "div", "style" -> {
                    requireHotspotMarkup(uri in HtmlNamespaces && parent in HtmlContainers)
                    if (name == "div") requireHotspotMarkup(values.keys.all { it in HtmlDivAttributes })
                    else {
                        requireHotspotMarkup(values.isEmpty())
                        css.clear()
                    }
                }
                else -> {
                    requireHotspotMarkup(uri == EpubSvgNamespace)
                    when (name) {
                        "svg" -> requireHotspotMarkup(parent in HtmlContainers &&
                            hasLayoutOnlyEpubSvgAttributes(values, scoped = true))
                        "g" -> requireHotspotMarkup(parent in SvgContainers &&
                            hasLayoutOnlyEpubSvgAttributes(values, scoped = true))
                        "image" -> {
                            requireHotspotMarkup(parent in SvgContainers && values.keys.all { it in SvgImageAttributes })
                            requireHotspotMarkup(listOfNotNull(values["href"], values["xlink:href"]).size == 1)
                            images++
                        }
                        "title", "desc" -> requireHotspotMarkup(parent in SvgContainers && values.isEmpty())
                        "a" -> {
                            val references = epubHotspotReferences(values)
                            // Kavita leaves these archive filenames alone. They are inert, not URLs
                            // to load; the processed fragment has no archive path-to-spine table.
                            requireHotspotMarkup(parent in SvgContainers &&
                                references?.all { LocalSpineFilename.matches(it) } == true)
                            rectsInLink = 0
                        }
                        "rect" -> {
                            requireHotspotMarkup(parent == "a" && isTransparentEpubHotspotRect(values))
                            rectsInLink++
                        }
                        else -> throw SAXException()
                    }
                }
            }
            stack += name
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            when (stack.lastOrNull()) {
                "style" -> css.append(chars, start, length)
                "title", "desc" -> Unit
                else -> requireHotspotMarkup((start until start + length).all { chars[it].isWhitespace() })
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (stack.removeAt(stack.lastIndex)) {
                "a" -> {
                    requireHotspotMarkup(rectsInLink > 0)
                    hasHotspot = true
                }
                "style" -> requireHotspotMarkup(isLayoutOnlyEpubCss(css.toString(), scoped = true))
            }
        }

        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException() }
        override fun processingInstruction(target: String?, data: String?) { throw SAXException() }
        override fun startCDATA() { throw SAXException() }
        override fun comment(chars: CharArray, start: Int, length: Int) {
            // HTML style text retains markup that XML would hide from characters().
            requireHotspotMarkup(stack.lastOrNull() != "style")
        }
    }
    try {
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.contentHandler = handler
        // Preserve ampersands as literal text for validation: HTML style elements
        // do not decode entities, and Kavita also emits bare ampersands in image URLs.
        // The HTML parser, not this check, supplies the decoded image URL.
        reader.parse(InputSource(StringReader("<fragment>${html.replace("&", "&amp;")}</fragment>")))
    } catch (_: SAXException) {
        return false
    }
    return hasHotspot && images == 1
}

private fun requireHotspotMarkup(condition: Boolean) {
    if (!condition) throw SAXException()
}

private val HtmlNamespaces = setOf("", "http://www.w3.org/1999/xhtml")
private val HtmlContainers = setOf("fragment", "div")
private val SvgContainers = setOf("svg", "g")
private val HtmlDivAttributes = setOf("id", "class", "style")
private val SvgImageAttributes = setOf("href", "xlink:href", "class", "id", "x", "y", "width", "height",
    "preserveaspectratio", "preserveAspectRatio")
private val LocalSpineFilename = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.xhtml")
