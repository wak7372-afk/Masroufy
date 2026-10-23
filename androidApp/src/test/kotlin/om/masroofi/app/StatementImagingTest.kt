package om.masroofi.app

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.runBlocking
import om.masroofi.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StatementImagingTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun png():ByteArray {
        val image=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.WHITE)
        return try{ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()}finally{image.recycle()}
    }
    @Test fun detailStripsCoverWholePageWithOverlapAndBoundedMemory() {
        val tiles=StatementImaging.tiles(2480,3508)
        assertTrue(tiles.size>1)
        assertEquals(0.0,tiles.first().region.y,0.000001)
        assertEquals(1.0,tiles.last().region.y+tiles.last().region.height,0.000001)
        assertTrue(tiles.all{it.width.toLong()*it.height<=StatementImaging.MAX_PIXELS})
        assertTrue(tiles.zipWithNext().all{(a,b)->a.region.y+a.region.height>b.region.y})
        assertTrue(StatementImaging.tiles(600,100_000).isEmpty())
    }
    @Test fun overlappingWordsAreMergedAndDifferentNumericReadingsRequireReview() {
        val tileA=StatementImaging.Tile(StatementRegion(0.0,0.0,1.0,0.6),1000,600)
        val tileB=StatementImaging.Tile(StatementRegion(0.0,0.4,1.0,0.6),1000,600)
        val a=StatementPage(600.0,600.0,listOf(StatementWord("1.200",100.0,500.0,30.0,10.0,0.9)))
        val b=StatementPage(600.0,600.0,listOf(StatementWord("1.200",100.0,100.0,30.0,10.0,0.95)))
        val merged=StatementImaging.merge(600.0,600.0,listOf(tileA to a,tileB to b))
        assertEquals(1,merged.words.size);assertFalse(merged.ocrDisagreement)
        val conflict=StatementImaging.merge(600.0,600.0,listOf(tileA to a,tileB to b.copy(words=b.words.map{it.copy(text="7.200")})))
        assertEquals(1,conflict.words.size);assertTrue(conflict.ocrDisagreement)
    }
    @Test fun originalImageCropUsesFieldCoordinatesAndReleasesReviewSource()=runBlocking {
        val bytes=png();val source=StatementReviewSource(TransientStatementSource(bytes),"")
        val region=StatementRegion(0.2,0.2,0.25,0.1)
        val row=StatementRow("p1-r1",1,"13/10/2026",0,"Test",1200,"out","expense",null,null,emptyList(),true,region=StatementRegion(0.0,0.1,0.9,0.3))
        val crop=source.crop(context,row,StatementField("amount",region,0.7))
        try{assertTrue(crop.width<600);assertTrue(crop.height<200);assertTrue(crop.width.toLong()*crop.height<=StatementImaging.MAX_PIXELS)}finally{crop.eraseColor(Color.WHITE);crop.recycle()}
        source.close();assertTrue(bytes.all{it==0.toByte()})
        try{source.crop(context,row,null);fail("Closed source must not render") }catch(_:IllegalStateException){}
    }
    @Test fun mixedPdfWithReadableHeaderAndImageTableIsDetected() {
        PDFBoxResourceLoader.init(context)
        val picture=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
        picture.eraseColor(Color.WHITE)
        val bytes=try{PDDocument().use { document ->
            val page=PDPage();document.addPage(page)
            PDPageContentStream(document,page).use { out ->
                repeat(6) { index ->
                    out.beginText();out.setFont(PDType1Font.HELVETICA,10f);out.newLineAtOffset(40f,760f-index*14f)
                    out.showText("NIZWA Bank account statement original header OMR");out.endText()
                }
                out.drawImage(LosslessFactory.createFromImage(document,picture),40f,200f,500f,300f)
            }
            ByteArrayOutputStream().also{document.save(it)}.toByteArray()
        }}finally{picture.recycle()}
        try {
            val page=StatementPdfReader.layout(context,bytes,""){}.pages.single()
            assertTrue(page.hasRasterContent);assertTrue(page.words.size>=5)
            assertTrue(OfflineStatementReader.requiresOcr(page,emptyList()))
            val row=StatementRow("p1-r1",1,"13/10/2026",0,"Test",1000,"out","expense",null,null,emptyList(),true)
            assertFalse(OfflineStatementReader.requiresOcr(page,listOf(row)))
        }finally{bytes.fill(0)}
    }
    @Test fun invalidCropCoordinatesNeverReachNativeRenderer() {
        listOf(StatementRegion(-0.1,0.0,1.0,0.1),StatementRegion(0.0,0.0,Double.NaN,0.1),StatementRegion(0.9,0.0,0.2,0.1)).forEach { region ->
            assertThrows(IllegalArgumentException::class.java){StatementImaging.validate(region)}
        }
    }
}
