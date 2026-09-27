package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import ca.mpreg.imagedecoder.ImageDecoder
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [Decoder] that uses [ImageDecoder] to decode image formats not supported
 * by the Android system decoder (AVIF, JXL, HEIF, etc.).
 */
class ImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    /**
     * Wraps a raw [ImageDecoder.Frame] as a Coil [Image] for callers that want
     * direct access to the RGBA [java.nio.ByteBuffer] (e.g. the new-decoder path).
     */
    class DecodeResultImage(
        val frame: ImageDecoder.Frame,
        val isHdr: Boolean,
        val hdrHeadroom: Float,
        val gainmap: ImageDecoder.Gainmap?,
    ) : Image {
        val image: java.nio.ByteBuffer get() = frame.image

        // Taken now: a caller may close the frame once it has the pixels.
        override val size: Long = frame.image.capacity().toLong()
        override val width: Int get() = frame.width
        override val height: Int get() = frame.height
        override val shareable: Boolean get() = true
        override fun draw(canvas: Canvas) {}
    }

    override suspend fun decode(): DecodeResult {
        val res = resources.source().use {
            ImageDecoder.open(it.inputStream()).use { dec ->
                DecodeResultImage(
                    dec.decodeNext(),
                    dec.isHdr,
                    dec.hdrHeadroom,
                    if (dec.hdrKind == ImageDecoder.HdrKind.GAINMAP) dec.getGainmap() else null,
                )
            }
        }

        val srcWidth = res.width
        val srcHeight = res.height

        // newDecoder path: caller wants the raw DecodeResult (e.g. for custom rendering).
        // Hand it back as-is; sampling is the caller's responsibility.
        if (options.newDecoder) {
            return DecodeResult(
                image = res,
                isSampled = false,
            )
        }

        // Normal path: produce a Bitmap scaled to the requested output size.
        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        val scale = min(dstWidth.toFloat() / srcWidth, dstHeight.toFloat() / srcHeight)
        val width = (srcWidth * scale).roundToInt().coerceIn(1, srcWidth)
        val height = (srcHeight * scale).roundToInt().coerceIn(1, srcHeight)

        val config = if (res.isHdr) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888

        // Downsample if needed. sampleSize is a power-of-two factor; the target
        // dimensions are src / sampleSize, matching BitmapFactory inSampleSize behaviour.
        val bitmap = if (width < srcWidth || height < srcHeight) {
            val resized = if (res.isHdr) {
                ca.mpreg.webgpuviewer.ImageUtil.resizeF16(
                    res.image,
                    res.width,
                    res.height,
                    width,
                    height,
                )
            } else {
                ca.mpreg.webgpuviewer.ImageUtil.resize(
                    res.image,
                    res.width,
                    res.height,
                    width,
                    height,
                )
            }
            val resizedBitmap = createBitmap(width, height, config)
            resizedBitmap.copyPixelsFromBuffer(resized)
            resizedBitmap
        } else {
            // Copy RGBA pixels from the native buffer into a full-resolution bitmap.
            // We must do this while `res` (and its native memory) is still alive.
            // HDR frames are half-float RGBA.
            val fullBitmap = createBitmap(srcWidth, srcHeight, config)
            res.image.rewind()
            fullBitmap.copyPixelsFromBuffer(res.image)
            res.frame.close()
            fullBitmap
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = width < srcWidth || height < srcHeight,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.newDecoder || options.customDecoder || isApplicable(result.source.source())) {
                ImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().use {
                ImageUtil.findImageType(it)
            }
            return when (type) {
                ImageUtil.ImageType.AVIF,
                ImageUtil.ImageType.JXL,
                ImageUtil.ImageType.HEIF,
                ImageUtil.ImageType.JP2,
                -> true

                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }
}
