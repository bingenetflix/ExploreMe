package com.local.exploreme

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalPhoto(
    val id: Long,
    val displayName: String,
    val albumName: String,
    val dateTakenMs: Long,
    val formattedDate: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val relativePath: String,
    val contentUri: Uri
)

data class PhotoAlbum(
    val name: String,
    val coverUri: Uri,
    val photoCount: Int,
    val photos: List<LocalPhoto>
)

class LocalGalleryRepository(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("exploreme_favorites", Context.MODE_PRIVATE)
    }

    fun getFavoriteIds(): Set<Long> {
        val raw = prefs.getStringSet("fav_ids", emptySet()).orEmpty()
        return raw.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun toggleFavorite(photoId: Long): Set<Long> {
        val current = getFavoriteIds().toMutableSet()
        if (!current.add(photoId)) {
            current.remove(photoId)
        }
        prefs.edit().putStringSet("fav_ids", current.map { it.toString() }.toSet()).apply()
        return current
    }

    fun loadAllPhotos(): List<LocalPhoto> {
        val photos = mutableListOf<LocalPhoto>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val dateFormatter = SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault())

        try {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val rawName = cursor.getString(nameCol)?.trim().orEmpty().ifEmpty { "Photo_$id" }
                    val cleanTitle = rawName.substringBeforeLast('.')
                    val bucket = cursor.getString(bucketCol)?.trim().orEmpty().ifEmpty { "Camera" }
                    val taken = cursor.getLong(dateTakenCol)
                    val addedSec = cursor.getLong(dateAddedCol)
                    val timestampMs = if (taken > 0L) taken else addedSec * 1000L
                    val sizeBytes = cursor.getLong(sizeCol)
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val filePath = cursor.getString(dataCol).orEmpty()
                    val uri = ContentUris.withAppendedId(collection, id)

                    photos.add(
                        LocalPhoto(
                            id = id,
                            displayName = cleanTitle,
                            albumName = bucket,
                            dateTakenMs = timestampMs,
                            formattedDate = dateFormatter.format(Date(timestampMs)),
                            sizeBytes = sizeBytes,
                            width = width,
                            height = height,
                            relativePath = filePath,
                            contentUri = uri
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        return photos
    }

    fun groupAlbums(photos: List<LocalPhoto>, favoriteIds: Set<Long>): List<PhotoAlbum> {
        val grouped = photos.groupBy { it.albumName }
        val result = mutableListOf<PhotoAlbum>()

        val favPhotos = photos.filter { it.id in favoriteIds }
        if (favPhotos.isNotEmpty()) {
            result.add(
                PhotoAlbum(
                    name = "Favorites",
                    coverUri = favPhotos.first().contentUri,
                    photoCount = favPhotos.size,
                    photos = favPhotos
                )
            )
        }

        grouped.entries
            .sortedByDescending { it.value.size }
            .forEach { (albumName, items) ->
                if (items.isNotEmpty()) {
                    result.add(
                        PhotoAlbum(
                            name = albumName,
                            coverUri = items.first().contentUri,
                            photoCount = items.size,
                            photos = items
                        )
                    )
                }
            }

        return result
    }

    fun decodeDownsampledImage(uri: Uri, targetMaxPx: Int): ImageBitmap? {
        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            var sample = 1
            while (boundsOpts.outWidth / sample > targetMaxPx || boundsOpts.outHeight / sample > targetMaxPx) {
                sample *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                return BitmapFactory.decodeStream(stream, null, decodeOpts)?.asImageBitmap()
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun formatFileSize(bytes: Long): String {
        return Formatter.formatShortFileSize(context, bytes)
    }

    fun sharePhoto(uri: Uri) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share Photo").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {
        }
    }

    fun editPhotoInSystem(uri: Uri) {
        try {
            val editIntent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(editIntent)
        } catch (_: Exception) {
        }
    }

    fun openSystemCamera() {
        try {
            val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(camIntent)
        } catch (_: Exception) {
        }
    }

    fun buildDeleteRequestSender(uri: Uri): IntentSender? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
            } else {
                context.contentResolver.delete(uri, null, null)
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
