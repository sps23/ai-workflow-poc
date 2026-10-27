import org.apache.tika.Tika
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

@Ignore
class TikaCompatibilityTest {
    @Test
    fun `standard parser bundle retains common document formats`() {
        val types =
            AutoDetectParser().getSupportedTypes(ParseContext()).map { it.toString() }.toSet()
        assertTrue("text/html" in types)
        assertTrue("application/pdf" in types)
        assertTrue("application/vnd.openxmlformats-officedocument.wordprocessingml.document" in types)
    }

    @Test
    fun `Tika extracts HTML text`() {
        val text = Tika().parseToString("<html><body>Tika compatibility</body></html>".byteInputStream())
        assertTrue(text.contains("Tika compatibility"))
    }
}
