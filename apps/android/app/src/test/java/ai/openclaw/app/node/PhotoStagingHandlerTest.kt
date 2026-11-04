package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhotoStagingHandlerTest {
  @Test
  fun stageReturnsPreparedAlbumContract() =
    runTest {
      val store = FakePhotoStagingStore()
      val handler = PhotoStagingHandler(ApplicationProvider.getApplicationContext<Context>(), store)

      val result =
        handler.handleStage(
          """{"albumName":"HT-Listing-3","imageUrls":["https://img.example/1.jpg","https://img.example/2.jpg"]}""",
        )

      assertNull(result.error)
      val payload = Json.parseToJsonElement(requireNotNull(result.payloadJson)).jsonObject
      assertEquals("HT-Listing-3", payload.getValue("albumName").jsonPrimitive.content)
      assertEquals("2", payload.getValue("imageCount").jsonPrimitive.content)
      assertEquals("Pictures/HT/HT-Listing-3/", payload.getValue("relativePath").jsonPrimitive.content)
      assertEquals(2, store.lastStage?.imageUrls?.size)
    }

  @Test
  fun stageRejectsUnsafeAlbumAndNonHttpUrls() =
    runTest {
      val handler = PhotoStagingHandler(ApplicationProvider.getApplicationContext<Context>(), FakePhotoStagingStore())

      assertInvalid(handler.handleStage("""{"albumName":"../bad","imageUrls":["https://img.example/1.jpg"]}"""))
      assertInvalid(handler.handleStage("""{"albumName":"HT-Listing-3","imageUrls":["file:///tmp/1.jpg"]}"""))
    }

  @Test
  fun stageReportsStoreFailureWithoutReturningPartialSuccess() =
    runTest {
      val store = FakePhotoStagingStore(failure = IllegalStateException("image 2 download failed"))
      val handler = PhotoStagingHandler(ApplicationProvider.getApplicationContext<Context>(), store)

      val result =
        handler.handleStage(
          """{"albumName":"HT-Listing-3","imageUrls":["https://img.example/1.jpg","https://img.example/2.jpg"]}""",
        )

      assertEquals("PHOTOS_STAGE_FAILED", result.error?.code)
      assertEquals("PHOTOS_STAGE_FAILED: image 2 download failed", result.error?.message)
    }

  @Test
  fun cleanupReturnsDeletedCount() =
    runTest {
      val store = FakePhotoStagingStore(deletedCount = 5)
      val handler = PhotoStagingHandler(ApplicationProvider.getApplicationContext<Context>(), store)

      val result = handler.handleCleanup("""{"albumName":"HT-Listing-3"}""")

      assertNull(result.error)
      val payload = Json.parseToJsonElement(requireNotNull(result.payloadJson)).jsonObject
      assertEquals("5", payload.getValue("deletedCount").jsonPrimitive.content)
      assertEquals("HT-Listing-3", store.cleanedAlbum)
    }

  private fun assertInvalid(result: GatewaySession.InvokeResult) {
    assertEquals("INVALID_REQUEST", result.error?.code)
  }
}

private class FakePhotoStagingStore(
  private val failure: Throwable? = null,
  private val deletedCount: Int = 0,
) : PhotoStagingStore {
  var lastStage: PhotoStageRequest? = null
  var cleanedAlbum: String? = null

  override suspend fun stage(request: PhotoStageRequest): PhotoStageResult {
    failure?.let { throw it }
    lastStage = request
    return PhotoStageResult(request.albumName, request.imageUrls.size, "Pictures/HT/${request.albumName}/")
  }

  override fun cleanup(albumName: String): Int {
    cleanedAlbum = albumName
    return deletedCount
  }
}
