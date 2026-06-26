package com.stormer.glassplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class VideoItem(val uri: Uri, val name: String, val folder: String, val durationMs: Long)

/** Reads the device video library via MediaStore, grouped by folder. */
object VideoLibrary {

    fun queryAll(ctx: Context): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val hasBucket = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )
        @Suppress("DEPRECATION")
        if (hasBucket) projection.add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        else projection.add(MediaStore.Video.Media.DATA)

        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        runCatching {
            ctx.contentResolver.query(collection, projection.toTypedArray(), null, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                @Suppress("DEPRECATION")
                val folderCol = if (hasBucket)
                    c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                else
                    c.getColumnIndex(MediaStore.Video.Media.DATA)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "Video"
                    val dur = if (durCol >= 0) c.getLong(durCol) else 0L
                    val folderRaw = if (folderCol >= 0) c.getString(folderCol) else null
                    val folder = when {
                        hasBucket -> folderRaw ?: "Other"
                        folderRaw != null -> folderRaw.substringBeforeLast('/').substringAfterLast('/')
                        else -> "Other"
                    }
                    out.add(VideoItem(ContentUris.withAppendedId(collection, id), name, folder, dur))
                }
            }
        }
        return out
    }

    /** Folder names, with Movies first, then alphabetical. */
    fun folders(items: List<VideoItem>): List<String> =
        items.map { it.folder }.distinct()
            .sortedWith(compareByDescending<String> { it.equals("Movies", true) }.thenBy { it.lowercase() })
}
