// Додаток навмисно без AndroidX, тож framework ExifInterface. Він використовується лише на Android 9 і старіших і лише для
// власних знімків додатка (небезпечні випадки android.media.ExifInterface стосуються чужих файлів).
@file:SuppressLint("ExifInterface")

package com.example.nosleepcam.camera

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.File
import java.io.IOException

/**
 * Збереження знімків у галерею (папка DCIM/Camera, ім'я IMG_ГГГГММДД_ГГММСС.jpg — як у камері Huawei) і мініатюра
 * останнього знімка. З Android 10 пишемо через MediaStore без жодних дозволів; на старіших системах потрібен
 * дозвіл WRITE_EXTERNAL_STORAGE (його просить MainActivity перед знімком).
 */
internal object PhotoSaver {

    private const val TAG = "NoSleepCam"
    private const val FOLDER = "DCIM/Camera"

    /** Записує JPEG і повертає його адресу в галереї; null — не вдалося. Викликати не з UI-потоку. */
    fun save(context: Context, jpeg: ByteArray, takenAtMs: Long): Uri? = try {
        if (Build.VERSION.SDK_INT >= 29) Api29.save(context, jpeg, takenAtMs) else saveLegacy(context, jpeg, takenAtMs)
    } catch (ex: Exception) {
        Log.w(TAG, "Save photo: ${ex.message}")
        null
    }

    /** Мініатюра зображення за адресою (≈[sizePx] по більшій стороні); null — зображення вже нема. Не з UI-потоку. */
    fun loadThumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) Api29.thumbnail(context, uri, sizePx) else thumbnailLegacy(context, uri, sizePx)
    } catch (ex: Exception) {
        Log.w(TAG, "Load thumbnail: ${ex.message}")
        null
    }

    @Suppress("DEPRECATION")
    @SuppressLint("InlinedApi")
    private fun saveLegacy(context: Context, jpeg: ByteArray, takenAtMs: Long): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")

        var duplicate = 0
        var file = File(dir, PhotoMath.fileName(takenAtMs))
        while (file.exists()) file = File(dir, PhotoMath.fileName(takenAtMs, ++duplicate))
        file.writeBytes(jpeg)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, takenAtMs)
            put(MediaStore.Images.Media.SIZE, file.length())
            put(MediaStore.Images.Media.DATA, file.absolutePath)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    @Suppress("DEPRECATION")
    private fun thumbnailLegacy(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    // Виклики API 29+ — в окремому класі (див. SystemBarInsets: так клас PhotoSaver не посилається на них напряму).
    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.Q)
    private object Api29 {
        fun save(context: Context, jpeg: ByteArray, takenAtMs: Long): Uri? {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, PhotoMath.fileName(takenAtMs))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, takenAtMs)
                put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
                // Поки файл пишеться, галерея його не показує (інакше в ній з'явиться напівзаписаний знімок).
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return null

            try {
                resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: throw IOException("No output stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (ex: Exception) {
                // Прибираємо лише власний недописаний запис.
                resolver.delete(uri, null, null)
                throw ex
            }
            return uri
        }

        fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? =
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
    }
}
