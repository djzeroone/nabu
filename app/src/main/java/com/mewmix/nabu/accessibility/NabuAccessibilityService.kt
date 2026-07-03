package com.mewmix.nabu.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class NabuAccessibilityService : AccessibilityService() {
    private val observationLock = Any()

    @Volatile
    private var lastObservationId: String? = null

    @Volatile
    private var lastObservedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonController.registerAccessibilityButtonCallback(object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) {
                    super.onClicked(controller)
                    val intent = android.content.Intent(this@NabuAccessibilityService, com.mewmix.nabu.ChatActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                }
            })
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit



    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun takeScreenshotToPath(destPath: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        File(destPath).parentFile?.mkdirs()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshotResult.hardwareBuffer,
                        screenshotResult.colorSpace
                    )
                    try {
                        val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        if (copy == null) {
                            deferred.complete(false)
                            return
                        }
                        FileOutputStream(File(destPath)).use { out ->
                            copy.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        copy.recycle()
                        deferred.complete(true)
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to save screenshot", error)
                        deferred.complete(false)
                    } finally {
                        bitmap?.recycle()
                        screenshotResult.hardwareBuffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Failed to take screenshot: $errorCode")
                    deferred.complete(false)
                }
            }
        )
        return kotlinx.coroutines.runBlocking { deferred.await() }
    }

    fun observeUi(xmlPath: String, screenshotPath: String?): JSONObject = synchronized(observationLock) {
        val window = targetWindow() ?: throw IllegalStateException("No active application window is available.")
        val root = window.root ?: throw IllegalStateException("The active application window has no accessibility root.")
        val observationId = UUID.randomUUID().toString()
        val packageName = root.packageName?.toString().orEmpty()
        val capturedAt = System.currentTimeMillis()
        if (!writeHierarchy(root, window, observationId, xmlPath)) {
            throw IllegalStateException("Failed to capture UI hierarchy.")
        }
        var actualScreenshotPath: String? = null
        if (!screenshotPath.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (takeScreenshotToPath(screenshotPath)) {
                actualScreenshotPath = screenshotPath
            }
        }
        lastObservationId = observationId
        lastObservedPackage = packageName
        val result = JSONObject()
            .put("schema_version", 2)
            .put("observation_id", observationId)
            .put("captured_at_ms", capturedAt)
            .put("package", packageName)
            .put("window_title", window.title?.toString().orEmpty())
            .put("rotation", runCatching { display?.rotation ?: 0 }.getOrDefault(0))
            .put("display_bounds", JSONArray(listOf(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)))
            .put("xml_path", xmlPath)
        if (actualScreenshotPath != null) {
            result.put("screenshot_path", actualScreenshotPath)
        }
        result
    }

    fun dumpScreenToXml(destPath: String): Boolean {
        val window = targetWindow() ?: return false
        val root = window.root ?: return false
        return writeHierarchy(root, window, UUID.randomUUID().toString(), destPath)
    }

    fun performUiAction(action: String, params: JSONObject): JSONObject = synchronized(observationLock) {
        val observationId = params.optString("observation_id").trim()
        require(observationId.isNotEmpty()) { "observation_id is required." }
        require(observationId == lastObservationId) { "Stale or unknown observation_id." }
        val window = targetWindow() ?: throw IllegalStateException("No active application window is available.")
        val root = window.root ?: throw IllegalStateException("The active application window has no accessibility root.")
        val currentPackage = root.packageName?.toString().orEmpty()
        require(currentPackage == lastObservedPackage) { "Active package changed since observation." }

        val selector = params.optJSONObject("selector") ?: JSONObject()
        val target = findNode(root, selector)
        val success = when (action) {
            "ui_tap" -> {
                val nodeSuccess = target?.let { performNodeAction(it, AccessibilityNodeInfo.ACTION_CLICK) } == true
                if (nodeSuccess) {
                    params.put("mechanism", "node")
                    true
                } else {
                    val gestureSuccess = runCatching { dispatchPointGesture(params, longPress = false) }.getOrDefault(false)
                    if (gestureSuccess) params.put("mechanism", "gesture")
                    gestureSuccess
                }
            }
            "ui_long_press" -> {
                val nodeSuccess = target?.let { performNodeAction(it, AccessibilityNodeInfo.ACTION_LONG_CLICK) } == true
                if (nodeSuccess) {
                    params.put("mechanism", "node")
                    true
                } else {
                    val gestureSuccess = runCatching { dispatchPointGesture(params, longPress = true) }.getOrDefault(false)
                    if (gestureSuccess) params.put("mechanism", "gesture")
                    gestureSuccess
                }
            }
            "ui_set_text" -> {
                val text = params.optString("text")
                require(text.isNotEmpty()) { "text is required." }
                val node = target ?: throw IllegalArgumentException("Target node was not found.")
                require(!node.isPassword) { "Password fields cannot be targeted." }
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            "ui_scroll" -> {
                val direction = params.optString("direction").lowercase()
                val node = target ?: findScrollableAncestor(root)
                    ?: throw IllegalArgumentException("Scrollable target was not found.")
                val scrollAction = when (direction) {
                    "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> throw IllegalArgumentException("Unsupported scroll direction '${direction}'.")
                }
                performNodeAction(node, scrollAction)
            }
            "ui_global_action" -> when (params.optString("global_action").lowercase()) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                else -> throw IllegalArgumentException("global_action must be back or home.")
            }
            else -> throw IllegalArgumentException("Unknown UI action: ${action}")
        }
        if (!success) throw IllegalStateException("Accessibility action '${action}' failed.")
        lastObservationId = null
        val result = JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("observation_id", observationId)
            .put("package", currentPackage)
        if (params.has("mechanism")) {
            result.put("mechanism", params.getString("mechanism"))
        }
        result
    }

    private fun targetWindow(): AccessibilityWindowInfo? = windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            it.root?.packageName?.toString() != packageName
    } ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

    private fun writeHierarchy(
        root: AccessibilityNodeInfo,
        window: AccessibilityWindowInfo,
        observationId: String,
        destPath: String
    ): Boolean = try {
        File(destPath).parentFile?.mkdirs()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val hierarchy = document.createElement("hierarchy").apply {
            setAttribute("schema-version", "2")
            setAttribute("observation-id", observationId)
            setAttribute("package", root.packageName?.toString().orEmpty())
            setAttribute("window-title", window.title?.toString().orEmpty())
            val rotation = runCatching { display?.rotation ?: 0 }.getOrDefault(0)
            setAttribute("rotation", rotation.toString())
        }
        document.appendChild(hierarchy)
        buildXmlTree(root, hierarchy, document, "0")
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            transform(DOMSource(document), StreamResult(File(destPath)))
        }
        true
    } catch (error: Exception) {
        Log.e(TAG, "Failed to dump XML", error)
        false
    }

    private fun buildXmlTree(node: AccessibilityNodeInfo, parent: Element, document: Document, path: String) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val element = document.createElement("node").apply {
            setAttribute("tree-path", path)
            setAttribute("package", node.packageName?.toString().orEmpty())
            setAttribute("resource-id", node.viewIdResourceName.orEmpty())
            setAttribute("class", node.className?.toString().orEmpty())
            setAttribute("text", node.text?.toString().orEmpty())
            setAttribute("content-desc", node.contentDescription?.toString().orEmpty())
            setAttribute("checkable", node.isCheckable.toString())
            setAttribute("checked", node.isChecked.toString())
            setAttribute("clickable", node.isClickable.toString())
            setAttribute("enabled", node.isEnabled.toString())
            setAttribute("editable", node.isEditable.toString())
            setAttribute("focusable", node.isFocusable.toString())
            setAttribute("focused", node.isFocused.toString())
            setAttribute("visible", node.isVisibleToUser.toString())
            setAttribute("scrollable", node.isScrollable.toString())
            setAttribute("long-clickable", node.isLongClickable.toString())
            setAttribute("password", node.isPassword.toString())
            setAttribute("selected", node.isSelected.toString())
            setAttribute("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        }
        parent.appendChild(element)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> buildXmlTree(child, element, document, "${path}/${index}") }
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, selector: JSONObject): AccessibilityNodeInfo? {
        val treePath = selector.optString("tree_path").trim()
        if (treePath.isNotEmpty()) findByTreePath(root, treePath)?.let { node ->
            if (matchesSelector(node, selector)) return node
        }
        if (matchesSelector(root, selector)) return root
        for (index in 0 until root.childCount) {
            root.getChild(index)?.let { child -> findNode(child, selector)?.let { return it } }
        }
        return null
    }

    private fun findByTreePath(root: AccessibilityNodeInfo, treePath: String): AccessibilityNodeInfo? {
        val indexes = treePath.split('/').mapNotNull(String::toIntOrNull)
        if (indexes.isEmpty() || indexes.first() != 0) return null
        var current = root
        for (index in indexes.drop(1)) current = current.getChild(index) ?: return null
        return current
    }

    private fun matchesSelector(node: AccessibilityNodeInfo, selector: JSONObject): Boolean {
        val fields = listOf(
            "resource_id" to node.viewIdResourceName.orEmpty(),
            "text" to node.text?.toString().orEmpty(),
            "content_desc" to node.contentDescription?.toString().orEmpty(),
            "class" to node.className?.toString().orEmpty()
        )
        var constrained = false
        for ((name, actual) in fields) {
            val expected = selector.optString(name).trim()
            if (expected.isNotEmpty()) {
                constrained = true
                if (actual != expected) return false
            }
        }
        return constrained || selector.optString("tree_path").isNotEmpty()
    }

    private fun performNodeAction(node: AccessibilityNodeInfo, action: Int): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.actionList.any { it.id == action } && current.performAction(action)) return true
            current = current.parent
        }
        return false
    }

    private fun findScrollableAncestor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (index in 0 until root.childCount) {
            root.getChild(index)?.let { findScrollableAncestor(it)?.let { node -> return node } }
        }
        return null
    }

    private fun dispatchPointGesture(params: JSONObject, longPress: Boolean): Boolean {
        val bounds = params.optJSONArray("fallback_bounds")
            ?: throw IllegalArgumentException("Target node and fallback_bounds are unavailable.")
        require(bounds.length() == 4) { "fallback_bounds must contain four integers." }
        val left = bounds.getInt(0)
        val top = bounds.getInt(1)
        val right = bounds.getInt(2)
        val bottom = bounds.getInt(3)
        require(right > left && bottom > top) { "fallback_bounds are invalid." }
        require(left >= 0 && top >= 0 && right <= resources.displayMetrics.widthPixels && bottom <= resources.displayMetrics.heightPixels) {
            "fallback_bounds are outside the display."
        }
        val path = Path().apply { moveTo((left + right) / 2f, (top + bottom) / 2f) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, if (longPress) 650 else 80))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                deferred.complete(false)
            }
        }, null)
        return kotlinx.coroutines.runBlocking { deferred.await() }
    }

    companion object {
        private const val TAG = "NabuAccessibility"

        @Volatile
        var instance: NabuAccessibilityService? = null
            private set
    }
}
