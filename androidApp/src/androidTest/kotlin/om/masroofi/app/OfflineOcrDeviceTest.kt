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
