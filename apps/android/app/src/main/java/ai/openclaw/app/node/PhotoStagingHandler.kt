package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.ui.image.SafeWebFetcher
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.util.Locale

private const val MAX_STAGED_IMAGES = 20
private const val MAX_STAGED_IMAGE_BYTES = 15 * 1024 * 1024
private const val STAGING_DOWNLOAD_TIMEOUT_MS = 30_000L
private const val STAGED_FILE_PREFIX = "openclaw-"
private val validAlbumName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
private val stagedImageContentTypes = setOf("image/gif", "image/jpeg", "image/png", "image/webp")

internal data class PhotoStageRequest(
  val albumName: String,
  val imageUrls: List<String>,
)

@Serializable
internal data class PhotoStageResult(
  val albumName: String,
  val imageCount: Int,
  val relativePath: String,
)

internal interface PhotoStagingStore {
  suspend fun stage(request: PhotoStageRequest): PhotoStageResult

  fun cleanup(albumName: String): Int
}

private class SystemPhotoStagingStore(
  context: Context,
  private val resolver: ContentResolver = context.contentResolver,
  private val fetcher: SafeWebFetcher = SafeWebFetcher(timeoutMillis = STAGING_DOWNLOAD_TIMEOUT_MS),
) : PhotoStagingStore {
  private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

  override suspend fun stage(request: PhotoStageRequest): PhotoStageResult {
    cleanup(request.albumName)
    val relativePath = relativePath(request.albumName)
    val inserted = mutableListOf<Uri>()
    try {
      request.imageUrls.forEachIndexed { index, url ->
        val image = fetchImage(url) ?: fetchImage(url) ?: error("image ${index + 1} download failed")
        val extension = extensionFor(image.contentType)
        val displayName = "$STAGED_FILE_PREFIX${(index + 1).toString().padStart(2, '0')}.$extension"
        val values =
          ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, image.contentType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis() + request.imageUrls.size - index)
            put(MediaStore.Images.Media.IS_PENDING, 1)
          }
        val uri = resolver.insert(collection, values) ?: error("image ${index + 1} media insert failed")
        inserted += uri
        resolver.openOutputStream(uri, "w").use { output ->
          checkNotNull(output) { "image ${index + 1} media output unavailable" }
          output.write(image.bytes)
        }
        val published =
          resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
          )
        check(published == 1) { "image ${index + 1} media publish failed" }
      }
      return PhotoStageResult(
        albumName = request.albumName,
        imageCount = request.imageUrls.size,
        relativePath = relativePath,
      )
    } catch (error: Throwable) {
      inserted.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
      throw error
    }
  }

  override fun cleanup(albumName: String): Int =
    resolver.delete(
      collection,
      "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
      arrayOf(relativePath(albumName), "$STAGED_FILE_PREFIX%"),
    )

  private suspend fun fetchImage(url: String) =
    fetcher.fetch(
      originalUrl = url,
      accept = "image/*",
      allowedContentTypes = stagedImageContentTypes,
      maxBytes = MAX_STAGED_IMAGE_BYTES,
      rejectOversizedBody = true,
    )

  private fun extensionFor(contentType: String): String =
    when (contentType.lowercase(Locale.US)) {
      "image/gif" -> "gif"
      "image/png" -> "png"
      "image/webp" -> "webp"
      else -> "jpg"
    }
}

class PhotoStagingHandler internal constructor(
  appContext: Context,
  private val store: PhotoStagingStore = SystemPhotoStagingStore(appContext),
) {
  private val mutex = Mutex()

  suspend fun handleStage(paramsJson: String?): GatewaySession.InvokeResult {
    val request = parseStageRequest(paramsJson) ?: return nodeInvokeError("INVALID_REQUEST", "albumName and imageUrls required")
    return try {
      val result = mutex.withLock { store.stage(request) }
      GatewaySession.InvokeResult.ok(Json.encodeToString(result))
    } catch (error: Throwable) {
      nodeInvokeError("PHOTOS_STAGE_FAILED", error.message ?: "photo staging failed")
    }
  }

  suspend fun handleCleanup(paramsJson: String?): GatewaySession.InvokeResult {
    val albumName = parseAlbumName(paramsJson) ?: return nodeInvokeError("INVALID_REQUEST", "valid albumName required")
    return try {
      val deleted = mutex.withLock { store.cleanup(albumName) }
      GatewaySession.InvokeResult.ok("{\"albumName\":${Json.encodeToString(albumName)},\"deletedCount\":$deleted}")
    } catch (error: Throwable) {
      nodeInvokeError("PHOTOS_CLEANUP_FAILED", error.message ?: "photo cleanup failed")
    }
  }

  private fun parseStageRequest(paramsJson: String?): PhotoStageRequest? {
    val params = parseJsonParamsObject(paramsJson) ?: return null
    val albumName = parseAlbumName(paramsJson) ?: return null
    val elements = (params["imageUrls"] as? JsonArray)?.takeIf { it.isNotEmpty() && it.size <= MAX_STAGED_IMAGES } ?: return null
    val urls =
      elements.map { element ->
        element.asStringOrNull()?.trim()?.takeIf(::isHttpUrl) ?: return null
      }
    return PhotoStageRequest(albumName = albumName, imageUrls = urls)
  }

  private fun parseAlbumName(paramsJson: String?): String? =
    parseJsonParamsObject(paramsJson)
      ?.let { parseJsonString(it, "albumName") }
      ?.trim()
      ?.takeIf(validAlbumName::matches)

  private fun isHttpUrl(value: String): Boolean =
    runCatching {
      val uri = Uri.parse(value)
      (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

private fun relativePath(albumName: String): String = "${Environment.DIRECTORY_PICTURES}/HT/$albumName/"
