package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import android.content.ClipboardManager
import android.content.Context
import kotlinx.serialization.json.Json

private const val MAX_CLIPBOARD_TEXT_LENGTH = 16 * 1024

internal fun interface ClipboardTextSource {
  fun readText(): String?
}

private class SystemClipboardTextSource(
  private val appContext: Context,
) : ClipboardTextSource {
  override fun readText(): String? {
    val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).text?.toString()
  }
}

class ClipboardHandler internal constructor(
  appContext: Context,
  private val source: ClipboardTextSource = SystemClipboardTextSource(appContext),
) {
  fun handleRead(_paramsJson: String?): GatewaySession.InvokeResult {
    return try {
      val text = source.readText()?.takeIf { it.isNotBlank() } ?: return nodeInvokeError("CLIPBOARD_EMPTY", "clipboard has no text")
      if (text.length > MAX_CLIPBOARD_TEXT_LENGTH) {
        return nodeInvokeError("CLIPBOARD_TOO_LARGE", "clipboard text exceeds $MAX_CLIPBOARD_TEXT_LENGTH characters")
      }
      GatewaySession.InvokeResult.ok("{\"text\":${Json.encodeToString(text)}}")
    } catch (_: SecurityException) {
      nodeInvokeError("CLIPBOARD_UNAVAILABLE", "Android blocked clipboard access")
    } catch (error: Throwable) {
      nodeInvokeError("CLIPBOARD_UNAVAILABLE", error.message ?: "clipboard read failed")
    }
  }
}
