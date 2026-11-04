package ai.openclaw.app.node

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardHandlerTest {
  @Test
  fun readReturnsClipboardText() {
    val handler =
      ClipboardHandler(ApplicationProvider.getApplicationContext<Context>()) {
        "https://www.facebook.com/marketplace/item/1075291222163087/?mibextid=test"
      }

    val result = handler.handleRead(null)

    assertNull(result.error)
    val payload = Json.parseToJsonElement(requireNotNull(result.payloadJson)).jsonObject
    assertEquals(
      "https://www.facebook.com/marketplace/item/1075291222163087/?mibextid=test",
      payload.getValue("text").jsonPrimitive.content,
    )
  }

  @Test
  fun readRejectsEmptyClipboard() {
    val handler = ClipboardHandler(ApplicationProvider.getApplicationContext<Context>()) { null }

    val result = handler.handleRead(null)

    assertEquals("CLIPBOARD_EMPTY", result.error?.code)
  }

  @Test
  fun readReportsAndroidClipboardRestriction() {
    val handler =
      ClipboardHandler(ApplicationProvider.getApplicationContext<Context>()) {
        throw SecurityException("blocked")
      }

    val result = handler.handleRead(null)

    assertEquals("CLIPBOARD_UNAVAILABLE", result.error?.code)
  }
}
