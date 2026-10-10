package app.monogatari.android.reader

import org.junit.Assert.*
import org.junit.Test

class UpscaleGeometryTest {
    @Test fun fixedRectangularImageAndOriginalPositiveIntegerTileShapesAreAccepted() {
        UpscaleGeometry.validate("image",7,5,42,15,7,0,1,0)
        UpscaleGeometry.validate("image",7,5,10,8,7,0,1,0)
        UpscaleGeometry.validate("multiarray",3,3,15,15,3,0,5,0)
        UpscaleGeometry.validate("multiarray",513,513,1026,1026,513,0,2,0)
        UpscaleGeometry.validate("multiarray",1,1,10,10,1,0,10,0)
        assertEquals(35 to 25,UpscaleGeometry.scaledDimensions(7,5,5))
    }
    @Test fun actualPixelBudgetAndIntegerOverflowAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UpscaleGeometry.checkPixels(8000,8000) }
        assertThrows(IllegalArgumentException::class.java) { UpscaleGeometry.scaledDimensions(Int.MAX_VALUE,2,Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { UpscaleGeometry.scaledDimensions(4000,4000,2) }
        assertThrows(IllegalArgumentException::class.java) { UpscaleGeometry.checkPixels(0,3) }
    }
    @Test fun invalidTileHaloAndMetadataOutputShapesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UpscaleGeometry.validate("multiarray",3,3,15,14,3,0,5,0) }
        assertThrows(IllegalArgumentException::class.java) { UpscaleGeometry.validate("multiarray",3,3,15,15,3,2,5,0) }
        assertThrows(IllegalArgumentException::class.java) { UpscaleGeometry.validate("multiarray",3,3,15,15,3,0,5,3) }
        assertThrows(IllegalArgumentException::class.java) { UpscaleGeometry.validate("unknown",3,3,15,15,3,0,5,0) }
    }
}
