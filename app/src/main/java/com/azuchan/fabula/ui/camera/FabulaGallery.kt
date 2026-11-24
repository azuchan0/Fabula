package com.azuchan.fabula.ui.camera

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.azuchan.fabula.ui.theme.FabulaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo or video in Pictures/Fabula or Movies/Fabula (sub-folders included). */
data class GalleryItem(val uri: Uri, val mimeType: String, val isVideo: Boolean, val name: String)

private val GalleryPermissions = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
)

/** Full access, or Android 14's "selected photos" access: either way, don't ask again. */
fun hasGalleryPermission(context: Context): Boolean = listOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Newest first. Without the media permissions, only files this install created are visible. */
suspend fun queryFabulaMedia(context: Context, limit: Int = 600): List<GalleryItem> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
    )
    val selection = "(${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? OR " +
        "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?) AND " +
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND " +
        "${MediaStore.Files.FileColumns.IS_PENDING} = 0"
    val arguments = arrayOf(
        "Pictures/Fabula/%",
        "Movies/Fabula/%",
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )
    val items = mutableListOf<GalleryItem>()
    runCatching {
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection,
            selection,
            arguments,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val type = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            while (cursor.moveToNext() && items.size < limit) {
                val isVideo = cursor.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                items += GalleryItem(
                    uri = ContentUris.withAppendedId(base, cursor.getLong(id)),
                    mimeType = cursor.getString(mime) ?: if (isVideo) "video/mp4" else "image/jpeg",
                    isVideo = isVideo,
                    name = cursor.getString(name).orEmpty(),
                )
            }
        }
    }
    items
}

@Composable
fun GalleryScreen(refreshKey: Int, onOpen: (GalleryItem) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var permissionVersion by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionVersion++ }
    LaunchedEffect(Unit) {
        if (!hasGalleryPermission(context)) permissionLauncher.launch(GalleryPermissions)
    }
    LaunchedEffect(refreshKey, permissionVersion) { items = queryFabulaMedia(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FabulaColors.Background)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTapTarget(description = "Back to camera", onClick = onBack) { BackIcon(FabulaColors.Text) }
            Text("Fabula", color = FabulaColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            items?.let { Text("${it.size}", color = FabulaColors.TextMuted, fontSize = 14.sp) }
        }
        val list = items
        if (list != null && list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No photos yet", color = FabulaColors.TextMuted, fontSize = 15.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list.orEmpty(), key = { it.uri.toString() }) { item -> GalleryTile(item, onOpen) }
            }
        }
    }
}

@Composable
private fun GalleryTile(item: GalleryItem, onOpen: (GalleryItem) -> Unit) {
    val thumbnail = rememberThumbnail(item.uri)
    val badge = when {
        item.isVideo -> "VIDEO"
        item.mimeType == "image/x-adobe-dng" -> "DNG"
        item.mimeType == "image/tiff" -> "TIFF"
        item.name.endsWith("_2x.jpg") -> "48MP"
        else -> null
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(FabulaColors.Raised)
            .clickable(role = Role.Button) { onOpen(item) }
            .semantics { contentDescription = item.name },
    ) {
        thumbnail?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        badge?.let {
            Text(
                it,
                color = FabulaColors.Text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(FabulaColors.Background.copy(alpha = 0.7f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}
