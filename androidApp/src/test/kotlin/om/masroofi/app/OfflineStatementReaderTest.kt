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
    @Test fun closingDuringRecognitionDefersErasureUntilNativeReaderReleasesBytes() {
        val bytes=byteArrayOf(1,2,3)
        val source=TransientStatementSource(bytes)
        assertThrows(Exception::class.java) { source.useBytes {
            source.close()
            assertArrayEquals(byteArrayOf(1,2,3),it)
            throw IllegalStateException("simulated cancellation")
        } }
        assertTrue(bytes.all { it==0.toByte() })
        assertThrows(Exception::class.java) { source.useBytes { } }
    }
    @Test fun temporaryBuffersAreErasedOnSuccessAndFailure() {
        val output=OfflineStatementReader.BoundedBytes(8)
        output.write(byteArrayOf(9,8,7))
        val field=java.io.ByteArrayOutputStream::class.java.getDeclaredField("buf").apply{isAccessible=true}
        val backing=field.get(output) as ByteArray
        val extracted=output.toByteArray()
        output.close()
        assertTrue(backing.all{it==0.toByte()});assertEquals(0,output.size())
        assertArrayEquals(byteArrayOf(9,8,7),extracted)
        val failed=OfflineStatementReader.BoundedBytes(2)
        val failedBacking=field.get(failed) as ByteArray
        failed.write(byteArrayOf(1,2))
        assertThrows(Exception::class.java){failed.use{it.write(3)}}
        assertTrue(failedBacking.all{it==0.toByte()})
    }
    @Test fun contrastRetryKeepsOriginalImageAndDimensions() {
        val input=Bitmap.createBitmap(640,400,Bitmap.Config.ARGB_8888)
        val improved=OfflineStatementReader.enhanceContrast(input)
        try {assertEquals(input.width,improved.width);assertEquals(input.height,improved.height);assertFalse(input.isRecycled)}
        finally{improved.recycle();input.recycle()}
    }
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
