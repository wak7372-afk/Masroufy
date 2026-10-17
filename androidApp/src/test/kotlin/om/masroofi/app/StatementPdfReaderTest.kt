package om.masroofi.app

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import om.masroofi.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** Real PDF bytes exercise PDFBox word extraction before the shared financial parser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StatementPdfReaderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now = 1_792_185_600_000L

    private fun pdf(password: String? = null, blankPages: Int = 0): ByteArray {
        PDFBoxResourceLoader.init(context)
        return PDDocument().use { doc ->
            if (blankPages > 0) repeat(blankPages) { doc.addPage(PDPage()) }
            else {
                val page = PDPage(); doc.addPage(page)
                PDPageContentStream(doc, page).use { out ->
                    fun text(value: String, x: Float, y: Float) {
                        out.beginText(); out.setFont(PDType1Font.HELVETICA, 10f)
                        out.newLineAtOffset(x, y); out.showText(value); out.endText()
                    }
                    text("Demo Statement OMR", 40f, 740f)
                    listOf("Date" to 40f, "Description" to 150f, "Debit" to 380f, "Credit" to 450f, "Balance" to 520f).forEach { (value, x) -> text(value, x, 700f) }
                    text("13/10/2026", 40f, 680f); text("OMAN OIL", 150f, 680f); text("3.000", 380f, 680f); text("999.000", 520f, 680f)
                    text("14/10/2026", 40f, 660f); text("Salary", 150f, 660f); text("2.000", 450f, 660f); text("1001.000", 520f, 660f)
                    text("Closing Balance", 150f, 630f); text("1001.000", 520f, 630f)
                }
            }
            if (password != null) doc.protect(StandardProtectionPolicy("demo-owner", password, AccessPermission()).apply { encryptionKeyLength = 128 })
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    @Test fun realPdfSeparatesTransactionsFromBalancesAndReplaysOnce() {
        val bytes = pdf()
        val (layout, id) = StatementPdfReader.extract(context, bytes, "") {}
        val engine = LedgerEngine()
        val preview = ledgerJson.decodeFromString<StatementPreview>(engine.previewStatement(layout, id, now))
        assertEquals("OMR", preview.currency)
        assertEquals(2, preview.rows.size)
        assertTrue(preview.rows.all { it.canImport })
        assertEquals(setOf(3000L, 2000L), preview.rows.map { it.amountBaisa }.toSet())
        val selected = ledgerJson.encodeToString(preview.rows.map { it.id })
        val first = ledgerJson.decodeFromString<ImportResult>(engine.importStatement(ledgerJson.encodeToString(preview), selected, now, true))
        assertEquals(2, first.ids.size)
        assertEquals(3000L, BudgetEngine.report(engine.state, now).grossExpense)
        assertEquals(2000L, BudgetEngine.report(engine.state, now).incoming)
        val again = ledgerJson.decodeFromString<ImportResult>(engine.importStatement(ledgerJson.encodeToString(preview), selected, now, true))
        assertEquals(0, again.ids.size)
        assertEquals(2, again.duplicates)
    }

    @Test fun passwordIsRequiredAndCorrectPasswordReadsSameLayout() {
        val bytes = pdf("demo-user")
        assertThrows(InvalidPasswordException::class.java) { StatementPdfReader.extract(context, bytes, "wrong") {} }
        val (layout, id) = StatementPdfReader.extract(context, bytes, "demo-user") {}
        val preview = ledgerJson.decodeFromString<StatementPreview>(LedgerEngine().previewStatement(layout, id, now))
        assertEquals(2, preview.rows.count { it.canImport })
    }

    @Test fun blankPdfFailsClearlyInsteadOfAddingImaginaryTransactions() {
        val error = assertThrows(Exception::class.java) { StatementPdfReader.extract(context, pdf(blankPages = 1), "") {} }
        assertTrue(error.message.orEmpty().contains("نص"))
    }

    @Test fun excessivePageCountIsRejectedBeforeExtraction() {
        val error = assertThrows(Exception::class.java) { StatementPdfReader.extract(context, pdf(blankPages = 51), "") {} }
        assertTrue(error.message.orEmpty().contains("50"))
    }

    @Test fun twoLineNearbyDateHeadersKeepTransactionDateAndAmountsSeparate() {
        PDFBoxResourceLoader.init(context)
        val bytes = PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { out ->
                fun text(value: String, x: Float, y: Float) {
                    out.beginText(); out.setFont(PDType1Font.HELVETICA, 9f)
                    out.newLineAtOffset(x, y); out.showText(value); out.endText()
                }
                text("Demo Statement OMR", 40f, 750f)
                text("Statement Period: 13/09/2026 to 17/10/2026", 40f, 730f)
                text("Value", 40f, 700f); text("Transaction", 110f, 700f)
                text("Date", 40f, 688f); text("Date", 110f, 688f)
                text("Description", 220f, 700f); text("Debit", 390f, 700f)
                text("Credit", 460f, 700f); text("Balance", 530f, 700f)
                text("13/09/2026", 40f, 665f); text("11/09/2026", 110f, 665f)
                text("POS-DEMO-001", 220f, 665f); text("1.200", 390f, 665f); text("100.000", 530f, 665f)
                text("14/09/2026", 40f, 645f); text("12/09/2026", 110f, 645f)
                text("Salary", 220f, 645f); text("2.000", 460f, 645f); text("102.000", 530f, 645f)
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val (layout, id) = StatementPdfReader.extract(context, bytes, "") {}
        val preview = ledgerJson.decodeFromString<StatementPreview>(LedgerEngine().previewStatement(layout, id, now))
        assertEquals(2, preview.rows.size)
        assertEquals(listOf("11/09/2026", "12/09/2026"), preview.rows.map { it.dateText })
        assertEquals(listOf(1200L, 2000L), preview.rows.map { it.amountBaisa })
        assertEquals(listOf("out", "in"), preview.rows.map { it.direction })
        assertTrue(preview.rows.all { it.canImport })
    }
}
