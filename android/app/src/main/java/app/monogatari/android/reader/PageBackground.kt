package app.monogatari.android.reader

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/** Direct port of the original PageBackground.swift border/streak classifier. */
object PageBackground {
    fun choose(image: Bitmap, landscape: Boolean): List<Int> {
        val w=image.width;val h=image.height;if(w<50 || h<50)return listOf(Color.WHITE)
        fun at(x:Int,y:Int)=image.getPixel(x.coerceIn(0,w-1),y.coerceIn(0,h-1))
        fun dark(p:Int)=Color.red(p)<40 && Color.green(p)<40 && Color.blue(p)<40 && Color.alpha(p)>200
        fun white(p:Int)=Color.red(p)+Color.green(p)+Color.blue(p)>740
        fun close(a:Int,b:Int)=abs(Color.red(a)-Color.red(b))<30 && abs(Color.green(a)-Color.green(b))<30 && abs(Color.blue(a)-Color.blue(b))<30
        val top=5;val bot=h-5;val left=(w*.0275).toInt();val right=w-left;val midX=w/2;val midY=h/2;val offset=(w*.01).toInt()
        val tl=at(left,top);val tr=at(right,top);val ml=at(left,midY);val mr=at(right,midY);val tm=at(midX,top);val bl=at(left,bot);val bm=at(midX,bot);val br=at(right,bot)
        var darkBg=dark(tl)&&(dark(bl)||dark(br)||dark(tr)||dark(ml)||dark(tm)) || dark(tr)&&(dark(br)||dark(bl)||dark(mr)||dark(tm))
        val around=listOf(tl,tm,tr,br,bm,bl)
        if(around.indices.all { !white(around[it]) && close(around[it],around[(it+1)%around.size]) })return listOf(tl)
        val corners=listOf(tl,tr,bl,br);if(corners.count(::white)>2)darkBg=false
        var black=corners.firstOrNull(::dark) ?: Color.WHITE
        var allWhite=0;var allBlack=0;var topBlack=0;var topWhite=0;var botBlack=0;var botWhite=0
        for(x in listOf(left,right,left-offset,right+offset)) {
            var whiteRun=0;var whites=0;var blackRun=0;var blacks=0;var hasBlack=false;var hasWhite=false
            val central=x==left||x==right
            for((i,y) in (0 until h step maxOf(1,h/25)).withIndex()) {
                val p=at(x,y);val off=at(x+(if(x<w/2)-offset else offset),y)
                if(white(p)) { whiteRun++;whites++;if(central)allWhite++;if(whiteRun>14)hasWhite=true;if(whiteRun>6 && whiteRun>=i-1)topWhite=whiteRun }
                else { whiteRun=0;if(dark(p)&&dark(off)){blacks++;if(central)allBlack++;blackRun++;if(blackRun>=14)hasBlack=true;continue} }
                if(blackRun>6 && blackRun>=i-1)topBlack=blackRun
                blackRun=0
            }
            if(blackRun>6)botBlack=blackRun else if(whiteRun>6)botWhite=whiteRun
            if(blacks>22 || hasBlack) { darkBg=true
                if(x==right||x==right+offset)black=if(dark(tr))tr else if(dark(br))br else black
                if(blacks>22||blacks>18){allWhite=0;break}
            } else if(hasWhite||whites>22)darkBg=false
        }
        val topStreak=topBlack>topWhite;val botStreak=botBlack>botWhite
        if(allWhite>9 && allWhite>allBlack)darkBg=false
        if(topStreak&&botStreak)darkBg=true
        if(landscape)return listOf(if(darkBg)black else Color.WHITE)
        return when {
            darkBg&&white(bl)&&white(br)->listOf(black,black,Color.WHITE,Color.WHITE)
            darkBg&&white(tl)&&white(tr)->listOf(Color.WHITE,Color.WHITE,black,black)
            darkBg->listOf(black)
            topStreak||dark(tl)&&dark(tr)&&dark(at(left-offset,top))&&dark(at(right+offset,top))&&(dark(tm)||allBlack>9)->listOf(black,black,Color.WHITE,Color.WHITE)
            botStreak||dark(bl)&&dark(br)&&dark(at(left-offset,bot))&&dark(at(right+offset,bot))&&(dark(bm)||allBlack>9)->listOf(Color.WHITE,Color.WHITE,black,black)
            else->listOf(Color.WHITE)
        }
    }
}
