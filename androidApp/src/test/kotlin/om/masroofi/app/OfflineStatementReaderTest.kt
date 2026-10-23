package om.masroofi.app

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OfflineStatementReaderTest {
    @Test fun memoryPdfReadsSupportSeekingAndEofWithoutWritingDocumentFiles() {
        val reader=OfflineStatementReader.MemoryPdfCallback(byteArrayOf(1,2,3,4,5))
        val buffer=ByteArray(4)
        assertEquals(5L,reader.onGetSize())
        assertEquals(2,reader.onRead(3,4,buffer)); assertArrayEquals(byteArrayOf(4,5,0,0),buffer)
        assertEquals(0,reader.onRead(100,4,buffer))
        assertEquals(4,reader.onRead(0,4,buffer)); assertArrayEquals(byteArrayOf(1,2,3,4),buffer)
    }
    @Test fun imageDecodeLimitsMemoryAndRejectsUnreadableTinyInput() {
        fun png(width:Int,height:Int):ByteArray {
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray() } finally { bitmap.recycle() }
        }
        val decoded=OfflineStatementReader.decodeImage(png(3000,2000))
        try { assertTrue(decoded.width.toLong()*decoded.height<=2_500_000) } finally { decoded.recycle() }
        assertThrows(Exception::class.java) { OfflineStatementReader.decodeImage(png(249,418)) }
        assertThrows(Exception::class.java) { OfflineStatementReader.decodeImage(byteArrayOf(1,2,3)) }
    }
}
