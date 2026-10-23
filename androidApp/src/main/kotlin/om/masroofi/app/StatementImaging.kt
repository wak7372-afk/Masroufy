package om.masroofi.app

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import om.masroofi.core.*
import java.io.ByteArrayInputStream
import kotlin.math.*

/** Each tile and review crop is bounded. Original document data never goes to a file. */
internal object StatementImaging {
    const val MAX_PIXELS=2_500_000
    data class Tile(val region:StatementRegion,val width:Int,val height:Int)
    fun tiles(width:Int,height:Int):List<Tile> {
        require(width>0 && height>0)
        val w=minOf(2400,width)
        val h=ceil(height.toDouble()*w/width).toInt()
        val stripHeight=minOf(1800,MAX_PIXELS/w)
        val result=mutableListOf<Tile>();var top=0
        while(top<h) {
            if(result.size>=12) return emptyList() // Never return a truncated page.
            val bottom=minOf(top+stripHeight,h)
            result+=Tile(StatementRegion(0.0,top.toDouble()/h,1.0,(bottom-top).toDouble()/h),w,bottom-top)
            if(bottom==h) break
            top=bottom-72
        }
        return result
    }
    internal fun merge(width:Double,height:Double,readings:List<Pair<Tile,StatementPage>>):StatementPage {
        val result=mutableListOf<StatementWord>();var disagreement=false
        readings.forEach { (tile,page) ->
            val previous=result.filter{it.y+it.height>=tile.region.y*height-2}
            page.words.forEach { word ->
                val mapped=word.copy(x=(tile.region.x+word.x/page.width*tile.region.width)*width,
                    y=(tile.region.y+word.y/page.height*tile.region.height)*height,
                    width=word.width/page.width*tile.region.width*width,height=word.height/page.height*tile.region.height*height)
                val other=previous.firstOrNull { abs(it.x+it.width/2-mapped.x-mapped.width/2)<maxOf(1.5,minOf(it.width,mapped.width)*0.3) &&
                    abs(it.y+it.height/2-mapped.y-mapped.height/2)<maxOf(1.5,minOf(it.height,mapped.height)*0.45) }
                if(other==null) result+=mapped
                else if(StatementImportText.normalize(other.text)!=StatementImportText.normalize(mapped.text)) {
                    disagreement=true
                    if(mapped.confidence>other.confidence){result.remove(other);result+=mapped}
                }
                if(result.size>100_000) throw StatementReadError("النص داخل الصورة أكبر من الحد المسموح")
            }
        }
        if(result.sumOf{it.text.length.toLong()}>1_000_000) throw StatementReadError("النص داخل الصورة أكبر من الحد المسموح")
        return StatementPage(width,height,result.sortedWith(compareBy<StatementWord>{it.y}.thenBy{it.x}),true,disagreement)
    }
    private object StatementImportText { fun normalize(value:String)=SmsParser.normalize(value).trim() }

    fun <T> withPdf(context:Context,source:ByteArray,password:String,action:(PdfRenderer)->T):T {
        var renderedBytes=source
        try {
            PDDocument.load(ByteArrayInputStream(source),password,MemoryUsageSetting.setupMainMemoryOnly(32L*1024*1024)).use { document ->
                if(!document.currentAccessPermission.canExtractContent()) throw StatementReadError("هذا الكشف لا يسمح باستخراج النص")
                if(document.isEncrypted) {
                    document.isAllSecurityToBeRemoved=true
                    OfflineStatementReader.BoundedBytes(40*1024*1024).use { output -> document.save(output);renderedBytes=output.toByteArray() }
                }
            }
            val thread=HandlerThread("statement-memory-review").apply{start()}
            try {
                val memory=OfflineStatementReader.MemoryPdfCallback(renderedBytes)
                context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,memory,Handler(thread.looper)).use { fd ->
                    PdfRenderer(fd).use { return action(it) }
                }
            } finally {thread.quitSafely()}
        } finally {if(renderedBytes!==source)renderedBytes.fill(0)}
    }
    fun render(page:PdfRenderer.Page,region:StatementRegion,width:Int,height:Int):Bitmap {
        validate(region)
        require(width>0 && height>0 && width.toLong()*height<=MAX_PIXELS)
        val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.WHITE)
            val sx=width.toFloat()/(region.width*page.width).toFloat()
            val sy=height.toFloat()/(region.height*page.height).toFloat()
            val matrix=Matrix().apply {setScale(sx,sy);postTranslate((-region.x*page.width*sx).toFloat(),(-region.y*page.height*sy).toFloat())}
            page.render(image,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return image
        } catch(error:Throwable){image.eraseColor(Color.WHITE);image.recycle();throw error}
    }
    private data class Orientation(val width:Int,val height:Int,val matrix:Matrix) {
        val upright:RectF=RectF(0f,0f,width.toFloat(),height.toFloat()).also{matrix.mapRect(it)}
    }
    private fun orientation(bytes:ByteArray):Orientation {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..100_000 && bounds.outHeight in 1..100_000)
        val exif=runCatching{ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix=Matrix().apply{when(exif){
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL->setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180->setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL->setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE->{setRotate(90f);postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_90->setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE->{setRotate(270f);postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_270->setRotate(270f)
        }}
        val extent=RectF(0f,0f,bounds.outWidth.toFloat(),bounds.outHeight.toFloat()).also{matrix.mapRect(it)}
        matrix.postTranslate(-extent.left,-extent.top)
        return Orientation(bounds.outWidth,bounds.outHeight,matrix)
    }
    @Suppress("DEPRECATION")
    private fun imageRegion(decoder:BitmapRegionDecoder,orientation:Orientation,region:StatementRegion,maxWidth:Int):Pair<Bitmap,StatementRegion> {
        validate(region)
        val whole=orientation.upright
        val requested=RectF((region.x*whole.width()).toFloat(),(region.y*whole.height()).toFloat(),((region.x+region.width)*whole.width()).toFloat(),((region.y+region.height)*whole.height()).toFloat())
        val inverse=Matrix();check(orientation.matrix.invert(inverse));inverse.mapRect(requested)
        val source=Rect(floor(requested.left).toInt().coerceIn(0,orientation.width-1),floor(requested.top).toInt().coerceIn(0,orientation.height-1),
            ceil(requested.right).toInt().coerceIn(1,orientation.width),ceil(requested.bottom).toInt().coerceIn(1,orientation.height))
        val upright=RectF(source).also{orientation.matrix.mapRect(it)}
        var sample=1
        while(ceil(upright.width()/sample).toLong()*ceil(upright.height()/sample).toLong()>MAX_PIXELS || ceil(upright.width()/sample)>maxWidth) sample*=2
        val decoded=decoder.decodeRegion(source,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888;inMutable=true}) ?: throw StatementReadError("تعذر تكبير الخانة من الصورة")
        val transformed=try{Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,orientation.matrix,true)}catch(error:Throwable){if(decoded.isMutable)decoded.eraseColor(Color.WHITE);decoded.recycle();throw error}
        if(transformed!==decoded){if(decoded.isMutable)decoded.eraseColor(Color.WHITE);decoded.recycle()}
        val opaque=Bitmap.createBitmap(transformed.width,transformed.height,Bitmap.Config.ARGB_8888)
        try{Canvas(opaque).apply{drawColor(Color.WHITE);drawBitmap(transformed,0f,0f,null)}}finally{if(transformed.isMutable)transformed.eraseColor(Color.WHITE);transformed.recycle()}
        return opaque to StatementRegion(upright.left/whole.width().toDouble(),upright.top/whole.height().toDouble(),upright.width()/whole.width().toDouble(),upright.height()/whole.height().toDouble())
    }
    @Suppress("DEPRECATION")
    fun imageTiles(bytes:ByteArray,recognize:(Bitmap)->StatementPage,checkActive:()->Unit):StatementPage? {
        val orientation=orientation(bytes);val whole=orientation.upright
        val plan=tiles(whole.width().roundToInt(),whole.height().roundToInt())
        if(plan.isEmpty()) return null
        val decoder=BitmapRegionDecoder.newInstance(bytes,0,bytes.size,false) ?: return null
        val readings=mutableListOf<Pair<Tile,StatementPage>>()
        try {
            plan.forEach { tile ->
                checkActive()
                val (bitmap,actual)=imageRegion(decoder,orientation,tile.region,2400)
                try {readings+=tile.copy(region=actual) to recognize(bitmap)}
                finally{bitmap.eraseColor(Color.WHITE);bitmap.recycle()}
            }
        } finally {decoder.recycle()}
        return merge(600.0,600.0*whole.height()/whole.width(),readings)
    }
    fun expanded(region:StatementRegion):StatementRegion {
        val left=(region.x-0.012).coerceAtLeast(0.0);val top=(region.y-0.006).coerceAtLeast(0.0)
        return StatementRegion(left,top,(region.x+region.width+0.012).coerceAtMost(1.0)-left,(region.y+region.height+0.006).coerceAtMost(1.0)-top)
    }
    fun validate(region:StatementRegion) {
        require(listOf(region.x,region.y,region.width,region.height).all{it.isFinite()} && region.x>=0 && region.y>=0 && region.width>0 && region.height>0 && region.x+region.width<=1.000001 && region.y+region.height<=1.000001)
    }
    @Suppress("DEPRECATION")
    fun crop(context:Context,bytes:ByteArray,password:String,row:StatementRow,focus:StatementField?):Bitmap {
        val requested=expanded(focus?.region ?: row.region ?: throw StatementReadError("لا تتوفر إحداثيات لهذه العملية"))
        val pdf=bytes.copyOfRange(0,minOf(bytes.size,1024)).toString(Charsets.ISO_8859_1).contains("%PDF-")
        return if(pdf) withPdf(context,bytes,password) { renderer ->
            if(row.page !in 1..renderer.pageCount) throw StatementReadError("صفحة العملية غير موجودة")
            renderer.openPage(row.page-1).use { page ->
                val ratio=requested.height*page.height/(requested.width*page.width)
                val w=minOf(1200.0,sqrt(MAX_PIXELS/ratio)).roundToInt().coerceAtLeast(1)
                val h=(w*ratio).roundToInt().coerceIn(1,MAX_PIXELS/w)
                render(page,requested,w,h)
            }
        } else {
            val decoder=BitmapRegionDecoder.newInstance(bytes,0,bytes.size,false) ?: throw StatementReadError("تعذر تكبير الصورة")
            try{imageRegion(decoder,orientation(bytes),requested,1600).first}finally{decoder.recycle()}
        }
    }
}

/** The single retained review source is RAM-only and is erased on success/cancel/disposal. */
internal class StatementReviewSource(private val source:TransientStatementSource,password:String):AutoCloseable {
    private val mutex=Mutex()
    private var password:String=password
    suspend fun crop(context:Context,row:StatementRow,focus:StatementField?):Bitmap=mutex.withLock {
        var image:Bitmap?=null
        try {withContext(Dispatchers.IO){source.useBytes{StatementImaging.crop(context,it,password,row,focus).also{result->image=result}}}}
        catch(error:Throwable){image?.let{it.eraseColor(Color.WHITE);it.recycle()};throw error}
    }
    override fun close(){password="";source.close()}
}
