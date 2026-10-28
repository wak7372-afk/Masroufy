package om.masroofi.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import om.masroofi.core.*
import kotlinx.serialization.decodeFromString

/** Runs the real native OCR on a device. Deliberately separate from JVM tests. */
@RunWith(AndroidJUnit4::class)
class OfflineOcrDeviceTest {
    @Test fun rebuiltNativePngDecodesActualFileAndReleasesResources() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val temporary=java.io.File.createTempFile("native-png-test-", ".png", context.cacheDir)
        val bitmap=Bitmap.createBitmap(800,160,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        canvas.drawText("9.650 OMR",40f,110f,Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.BLACK;textSize=80f;typeface=Typeface.MONOSPACE
        })
        val engine=com.googlecode.tesseract.android.TessBaseAPI()
        try {
            temporary.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val directory=OfflineStatementReader.prepareAssets(context)
            assertTrue(engine.init(directory.absolutePath,"eng",com.googlecode.tesseract.android.TessBaseAPI.OEM_LSTM_ONLY))
            engine.pageSegMode=com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            // File input deliberately exercises Leptonica -> rebuilt libpngx.so.
            engine.setImage(temporary)
            assertTrue(engine.getUTF8Text().orEmpty().contains("9.650"))
        } finally {
            engine.recycle();bitmap.eraseColor(Color.WHITE);bitmap.recycle()
            check(temporary.delete())
        }
        assertFalse(temporary.exists())
    }
    @Test fun pdfCropRendersOriginalAmountFromEncryptedAndRotatedPagesInMemory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        listOf(0,90,180,270).forEach { rotation ->
            val bytes=com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
                val page=com.tom_roush.pdfbox.pdmodel.PDPage();page.rotation=rotation;document.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document,page).use { out ->
                    out.beginText();out.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,14f)
                    out.newLineAtOffset(200f,600f);out.showText("9.650");out.endText()
                }
                document.protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","reader",com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()).apply{encryptionKeyLength=128})
                ByteArrayOutputStream().also{document.save(it)}.toByteArray()
            }
            try {
                val page=StatementPdfReader.layout(context,bytes,"reader"){}.pages.single()
                val word=page.words.single{it.text.contains("9.650")}
                val region=StatementRegion(word.x/page.width,word.y/page.height,word.width/page.width,word.height/page.height)
                val row=StatementRow("p1-r1",1,"13/10/2026",0,"Test",9650,"out","expense",null,null,emptyList(),true,region=region)
                val bitmap=StatementImaging.crop(context,bytes,"reader",row,null)
                try {
                    assertTrue(bitmap.width.toLong()*bitmap.height<=StatementImaging.MAX_PIXELS)
                    val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    assertTrue("Amount glyphs must be visible at rotation $rotation",pixels.count{Color.red(it)<100 && Color.green(it)<100 && Color.blue(it)<100}>30)
                    pixels.fill(0)
                } finally {bitmap.eraseColor(Color.WHITE);bitmap.recycle()}
            }finally{bytes.fill(0)}
        }
    }
    @Test fun nativeOcrReadsSyntheticStatementWithoutInternetPermission() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        val image=Bitmap.createBitmap(1800,1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image); canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=36f; typeface=Typeface.MONOSPACE }
        fun text(value:String,x:Float,y:Float){canvas.drawText(value,x,y,paint)}
        text("OMR",100f,80f)
        listOf("Date" to 100f,"Description" to 450f,"Debit" to 1100f,"Credit" to 1300f,"Balance" to 1500f).forEach{(t,x)->text(t,x,170f)}
        text("13/10/2026",100f,260f);text("TEST STORE",450f,260f);text("1.200",1100f,260f);text("98.800",1500f,260f)
        text("14/10/2026",100f,340f);text("Deposit",450f,340f);text("2.000",1300f,340f);text("100.800",1500f,340f)
        val bytes=try{ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()}finally{image.eraseColor(Color.WHITE);image.recycle()}
        try {
            val (layout,id)=OfflineStatementReader.extract(context,bytes,""){}
            val preview=ledgerJson.decodeFromString<StatementPreview>(LedgerEngine().previewStatement(layout,id,1_792_185_600_000L))
            assertEquals(listOf(1200L,2000L),preview.rows.map{it.amountBaisa})
            assertEquals(listOf("out","in"),preview.rows.map{it.direction})
            assertTrue(preview.rows.all{it.canImport && it.reviewRequired})
        } finally {bytes.fill(0)}
    }
}
