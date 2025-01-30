package no.ntnu.traininggame

import android.graphics.*
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Utility to convert ImageProxy (YUV_420_888) frames to ARGB_8888 Bitmaps
 * by creating an NV21 byte array and using YuvImage + JPEG decode.
 */
object YuvToRgbConverter {

    /**
     * Converts a YUV_420_888 ImageProxy to an ARGB_8888 Bitmap via NV21 + JPEG.
     */
    fun imageToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer // Y
        val uBuffer = imageProxy.planes[1].buffer // U
        val vBuffer = imageProxy.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // Construct an NV21 byte array (Y + V + U)
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // Convert NV21 to a Bitmap using YuvImage -> JPEG -> decodeByteArray
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )

        val outStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, outStream)
        val imageBytes = outStream.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
}
