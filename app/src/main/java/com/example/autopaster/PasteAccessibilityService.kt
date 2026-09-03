package com.example.autopaster

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

enum class PasteResult { OK, NO_FOCUSED_FIELD, FAILED }

class PasteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Writes [text] into whatever editable field is currently focused, in any app.
     * Strategy 1 (preferred): ACTION_SET_TEXT - sets the field's content directly,
     * works even when the target app doesn't implement paste handling.
     * Strategy 2 (fallback): put text on the clipboard and trigger ACTION_PASTE,
     * for fields that only support paste-in.
     * Never throws - any unexpected accessibility/system failure is caught and
     * reported back as PasteResult.FAILED instead of crashing the service.
     */
    fun pasteText(text: String): PasteResult {
        return try {
            pasteTextInternal(text)
        } catch (e: Exception) {
            PasteResult.FAILED
        }
    }

    private fun pasteTextInternal(text: String): PasteResult {
        val root = rootInActiveWindow ?: return PasteResult.NO_FOCUSED_FIELD
        try {
            val focused = findFocusedEditable(root) ?: return PasteResult.NO_FOCUSED_FIELD
            try {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                val setTextOk = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (setTextOk) return PasteResult.OK

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return PasteResult.FAILED
                clipboard.setPrimaryClip(ClipData.newPlainText("autopaster", text))
                val pasteOk = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                return if (pasteOk) PasteResult.OK else PasteResult.FAILED
            } finally {
                focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findFocusedEditable(
        node: AccessibilityNodeInfo,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        // Guard against pathological/cyclic view trees causing a stack overflow.
        if (depth > 60) return null

        if (node.isFocused && node.isEditable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child, depth + 1)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    companion object {
        var instance: PasteAccessibilityService? = null
    }
}
