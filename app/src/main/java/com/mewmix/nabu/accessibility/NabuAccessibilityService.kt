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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import kotlinx.coroutines.flow.asStateFlow
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

    @Volatile
    private var lastObservedFingerprint: String? = null

    private var actionOverlay: ActionSessionOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")
        actionOverlay = ActionSessionOverlay(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonController.registerAccessibilityButtonCallback(object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) {
                    super.onClicked(controller)
                    actionOverlay?.show()
                }
            })
        }
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    private var snapshotJob: kotlinx.coroutines.Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType in SNAPSHOT_EVENT_TYPES) {
            snapshotJob?.cancel()
            snapshotJob = serviceScope.launch {
                kotlinx.coroutines.delay(EVENT_DEBOUNCE_MS)
                captureEventSnapshot()
            }
        }
    }

    /** Captures a snapshot and atomically leases it for one UI action. */
    fun forceCaptureSnapshot(): UiSnapshot? {
        snapshotJob?.cancel()
        snapshotJob = null
        return synchronized(observationLock) {
            captureSnapshotLocked(bindForAction = true)
        }
    }

    private fun captureEventSnapshot() {
        synchronized(observationLock) {
            captureSnapshotLocked(bindForAction = false)
        }
    }

    private fun captureSnapshotLocked(bindForAction: Boolean): UiSnapshot? {
        return try {
            val window = targetWindow()
            val root = window?.root ?: rootInActiveWindow ?: return null
            val packageName = root.packageName?.toString().orEmpty()
            val windowTitle = window?.title?.toString().orEmpty()
            val rotation = runCatching { display?.rotation ?: 0 }.getOrDefault(0)

            val uiNodeRoot = buildUiNodeTree(root, "0")

            val snapshot = UiSnapshotStore.updateSnapshot(UiSnapshot(
                id = UUID.randomUUID().toString(),
                capturedAtMs = System.currentTimeMillis(),
                packageName = packageName,
                windowTitle = windowTitle,
                rotation = rotation,
                displayBounds = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels),
                rootNode = uiNodeRoot
            ))
            if (bindForAction) {
                lastObservationId = snapshot.id
                lastObservedPackage = packageName
                lastObservedFingerprint = snapshot.stateFingerprint
            } else {
                if (lastObservationId != null) {
                    val packageChanged = lastObservedPackage != null && lastObservedPackage != packageName
                    val fingerprintChanged = lastObservedFingerprint != null && lastObservedFingerprint != snapshot.stateFingerprint
                    if (packageChanged || fingerprintChanged) {
                        clearActionLeaseLocked()
                    }
                }
            }
            snapshot
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture in-memory snapshot", e)
            null
        }
    }

    private fun buildUiNodeTree(node: AccessibilityNodeInfo, path: String): UiNode {
        val bounds = Rect().also(node::getBoundsInScreen)
        val children = mutableListOf<UiNode>()
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                children.add(buildUiNodeTree(child, "$path/$index"))
            }
        }

        return UiNode(
            treePath = path,
            packageName = node.packageName?.toString().orEmpty(),
            resourceId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            text = if (node.isPassword) "•".repeat(node.text?.length ?: 8) else node.text?.toString().orEmpty(),
            contentDescription = if (node.isPassword) "•".repeat(node.contentDescription?.length ?: 8) else node.contentDescription?.toString().orEmpty(),
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isClickable = node.isClickable,
            isEnabled = node.isEnabled,
            isEditable = node.isEditable,
            isFocusable = node.isFocusable,
            isFocused = node.isFocused,
            isVisibleToUser = node.isVisibleToUser,
            isScrollable = node.isScrollable,
            isLongClickable = node.isLongClickable,
            isPassword = node.isPassword,
            isSelected = node.isSelected,
            boundsInScreen = bounds,
            children = children
        )
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        actionOverlay?.hide()
        actionOverlay = null
        if (instance == this) instance = null
        serviceScope.cancel()
        UiSnapshotStore.clear()
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
        val window = targetWindow()
        val root = window?.root ?: rootInActiveWindow ?: throw IllegalStateException("No active application window is available.")
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
        lastObservedFingerprint = null
        val result = JSONObject()
            .put("schema_version", 2)
            .put("observation_id", observationId)
            .put("captured_at_ms", capturedAt)
            .put("package", packageName)
            .put("window_title", window?.title?.toString().orEmpty())
            .put("rotation", runCatching { display?.rotation ?: 0 }.getOrDefault(0))
            .put("display_bounds", JSONArray(listOf(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)))
            .put("xml_path", xmlPath)
        if (actualScreenshotPath != null) {
            result.put("screenshot_path", actualScreenshotPath)
        }
        result
    }

    fun dumpScreenToXml(destPath: String): Boolean {
        val window = targetWindow()
        val root = window?.root ?: rootInActiveWindow ?: return false
        return writeHierarchy(root, window, UUID.randomUUID().toString(), destPath)
    }

    fun performUiAction(action: String, params: JSONObject): JSONObject = synchronized(observationLock) {
        val observationId = params.optString("observation_id").trim()
        require(observationId.isNotEmpty()) { "observation_id is required." }
        if (lastObservationId == null || observationId != lastObservationId) {
            throw IllegalStateException("Action observation lease is stale or invalid (expected $lastObservationId, got $observationId).")
        }
        val window = targetWindow()
        val root = window?.root ?: rootInActiveWindow ?: throw IllegalStateException("No active application window is available.")
        val currentPackage = root.packageName?.toString().orEmpty()
        if (lastObservedPackage == null || currentPackage != lastObservedPackage) {
            throw IllegalStateException("Active package changed since observation ($lastObservedPackage -> $currentPackage).")
        }
        clearActionLeaseLocked()

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
                var textSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (!textSuccess) {
                    val editableTarget = findEditableNode(node)
                    if (editableTarget != null && editableTarget != node) {
                        textSuccess = editableTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    }
                }
                if (!textSuccess) {
                    performNodeAction(node, AccessibilityNodeInfo.ACTION_CLICK)
                    textSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
                if (!textSuccess) {
                    runCatching { dispatchPointGesture(params, longPress = false) }
                    textSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
                textSuccess
            }
            "ui_scroll" -> {
                val direction = params.optString("direction").lowercase()
                val scrollAction = when (direction) {
                    "down", "right" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up", "left" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> throw IllegalArgumentException("Unsupported scroll direction '${direction}'.")
                }
                val node = target ?: findScrollableAncestor(root)
                val nodeSuccess = node?.let { runCatching { performNodeAction(it, scrollAction) }.getOrDefault(false) } ?: false
                if (nodeSuccess) {
                    true
                } else {
                    dispatchScrollGesture(direction, params)
                }
            }
            "ui_focus" -> {
                val node = target ?: throw IllegalArgumentException("Target node was not found.")
                performNodeAction(node, android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }
            "ui_global_action" -> when (params.optString("global_action").lowercase()) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                else -> throw IllegalArgumentException("Unsupported global_action: ${params.optString("global_action")}")
            }
            else -> throw IllegalArgumentException("Unknown UI action: ${action}")
        }
        if (!success) throw IllegalStateException("Accessibility action '${action}' failed.")
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

    private fun clearActionLeaseLocked() {
        lastObservationId = null
        lastObservedPackage = null
        lastObservedFingerprint = null
    }

    private fun targetWindow(): AccessibilityWindowInfo? {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return appWindows.firstOrNull { it.isFocused && it.root?.packageName?.toString() != packageName }
            ?: appWindows.firstOrNull { it.isActive && it.root?.packageName?.toString() != packageName }
            ?: appWindows.filter { it.root?.packageName?.toString() != packageName }.maxByOrNull { it.layer }
            ?: appWindows.firstOrNull { it.isFocused }
            ?: appWindows.firstOrNull { it.isActive }
            ?: appWindows.maxByOrNull { it.layer }
            ?: windows.firstOrNull()
    }

    private fun writeHierarchy(
        root: AccessibilityNodeInfo,
        window: AccessibilityWindowInfo?,
        observationId: String,
        destPath: String
    ): Boolean = try {
        File(destPath).parentFile?.mkdirs()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val hierarchy = document.createElement("hierarchy").apply {
            setAttribute("schema-version", "2")
            setAttribute("observation-id", observationId)
            setAttribute("package", root.packageName?.toString().orEmpty())
            setAttribute("window-title", window?.title?.toString().orEmpty())
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
            if (node.isPassword) {
                setAttribute("text", "•".repeat(node.text?.length ?: 8))
                setAttribute("content-desc", "•".repeat(node.contentDescription?.length ?: 8))
            } else {
                setAttribute("text", node.text?.toString().orEmpty())
                setAttribute("content-desc", node.contentDescription?.toString().orEmpty())
            }
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

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) return root
        for (index in 0 until root.childCount) {
            root.getChild(index)?.let { child ->
                findEditableNode(child)?.let { return it }
            }
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

    private fun dispatchScrollGesture(direction: String, params: JSONObject): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val bounds = params.optJSONArray("fallback_bounds")
        val (startX, startY, endX, endY) = if (bounds != null && bounds.length() == 4) {
            val left = bounds.getInt(0).toFloat()
            val top = bounds.getInt(1).toFloat()
            val right = bounds.getInt(2).toFloat()
            val bottom = bounds.getInt(3).toFloat()
            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f
            val deltaY = ((bottom - top) * 0.4f).coerceAtLeast(200f)
            val deltaX = ((right - left) * 0.4f).coerceAtLeast(200f)
            when (direction) {
                "down" -> listOf(centerX, (centerY + deltaY / 2f).coerceAtMost(screenHeight - 50f), centerX, (centerY - deltaY / 2f).coerceAtLeast(50f))
                "up" -> listOf(centerX, (centerY - deltaY / 2f).coerceAtLeast(50f), centerX, (centerY + deltaY / 2f).coerceAtMost(screenHeight - 50f))
                "right" -> listOf((centerX + deltaX / 2f).coerceAtMost(screenWidth - 50f), centerY, (centerX - deltaX / 2f).coerceAtLeast(50f), centerY)
                "left" -> listOf((centerX - deltaX / 2f).coerceAtLeast(50f), centerY, (centerX + deltaX / 2f).coerceAtMost(screenWidth - 50f), centerY)
                else -> listOf(centerX, (centerY + 200f).coerceAtMost(screenHeight - 50f), centerX, (centerY - 200f).coerceAtLeast(50f))
            }
        } else {
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            when (direction) {
                "down" -> listOf(centerX, screenHeight * 0.75f, centerX, screenHeight * 0.25f)
                "up" -> listOf(centerX, screenHeight * 0.25f, centerX, screenHeight * 0.75f)
                "right" -> listOf(screenWidth * 0.8f, centerY, screenWidth * 0.2f, centerY)
                "left" -> listOf(screenWidth * 0.2f, centerY, screenWidth * 0.8f, centerY)
                else -> listOf(centerX, screenHeight * 0.75f, centerX, screenHeight * 0.25f)
            }
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
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
        private const val EVENT_DEBOUNCE_MS = 40L
        private val SNAPSHOT_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )

        private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isConnected: kotlinx.coroutines.flow.StateFlow<Boolean> = _isConnected.asStateFlow()

        @Volatile
        var instance: NabuAccessibilityService? = null
            private set(value) {
                field = value
                _isConnected.value = value != null
            }
    }
}
