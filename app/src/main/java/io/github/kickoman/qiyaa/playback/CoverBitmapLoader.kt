package io.github.kickoman.qiyaa.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future

@UnstableApi
class CoverBitmapLoader(
    private val context: Context,
    private val images: ImageLoader,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = load(data, "of ${data.size} bytes")

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = load(uri.toString(), uri.toString())

    private fun load(data: Any, what: String): ListenableFuture<Bitmap> = scope.future(Dispatchers.Default) {
        val request =
            ImageRequest.Builder(context)
                .data(data)
                .size(MAX_DECODED_SIDE_PX)
                .precision(Precision.INEXACT)
                .allowHardware(false)
                .build()
        when (val result = images.execute(request)) {
            is SuccessResult -> result.image.toBitmap()
            is ErrorResult -> throw IOException("The cover $what did not load", result.throwable)
        }
    }

    companion object {
        const val MAX_DECODED_SIDE_PX = 1024
    }
}
