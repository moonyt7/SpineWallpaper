package com.spine.wallpaper.loader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.spine.wallpaper.bridge.AtlasSanitizer
import java.io.File
import java.io.FileOutputStream

/**
 * 把被 Unity 按 NPOT 规则拉伸过的贴图重采样回 atlas 声明的尺寸。
 *
 * 背景见 [AtlasSanitizer] 的注释：atlas 声明 1692x2020 而 PNG 实际 2048x2048 时，
 * libGDX 会用 2048 去归一化 bounds，采样位置整体错位，表现为「模型轮廓在、贴图全乱」。
 * 重采样回声明尺寸后 bounds/offsets 全部不用改，且不会引入各向异性误差。
 *
 * Android 的 Bitmap 内部按「前置乘 alpha」存储，createScaledBitmap(filter = true) 的插值
 * 也在该空间进行，因此半透明边缘不会出现发黑色环；compress(PNG) 时再还原成直通 alpha。
 */
object AtlasImageResampler : AtlasSanitizer.Resampler {

    private const val TAG = "SpineAtlasFix"

    override fun resample(source: File, width: Int, height: Int): File? {
        if (width <= 0 || height <= 0) return null
        val out = File(source.parentFile, "_fixed_" + source.nameWithoutExtension + ".png")

        // 已有同尺寸缓存就直接复用，避免每次加载都重算一遍（重采样一张 2048² 要几十毫秒）
        if (out.isFile) {
            val cached = AtlasSanitizer.readImageSize(out)
            if (cached != null && cached[0] == width && cached[1] == height) {
                Log.d(TAG, "reuse cached resampled texture ${out.name} (${width}x$height)")
                return out
            }
        }

        return try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val src = BitmapFactory.decodeFile(source.absolutePath, opts)
            if (src == null) {
                Log.w(TAG, "decode failed: ${source.name}")
                return null
            }
            if (src.width == width && src.height == height) {
                src.recycle()
                return null
            }
            val dst = Bitmap.createScaledBitmap(src, width, height, true)
            Log.d(
                TAG,
                "resample ${source.name} ${src.width}x${src.height} -> ${width}x$height (= atlas size:)"
            )
            src.recycle()
            val ok = try {
                FileOutputStream(out).use { fos ->
                    dst.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
            } finally {
                dst.recycle()
            }
            if (!ok) {
                Log.w(TAG, "encode failed: ${out.name}")
                return null
            }
            out
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }
}
