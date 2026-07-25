package com.mewmix.nabu.accessibility

import android.graphics.Rect

/**
 * A safe, lightweight representation of an AccessibilityNodeInfo that avoids
 * holding onto the original node references (which causes memory leaks).
 */
data class UiNode(
    val treePath: String,
    val packageName: String,
    val resourceId: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isClickable: Boolean,
    val isEnabled: Boolean,
    val isEditable: Boolean,
    val isFocusable: Boolean,
    val isFocused: Boolean,
    val isVisibleToUser: Boolean,
    val isScrollable: Boolean,
    val isLongClickable: Boolean,
    val isPassword: Boolean,
    val isSelected: Boolean,
    val boundsInScreen: Rect,
    val children: List<UiNode>
)

/**
 * A complete point-in-time capture of the UI state.
 */
data class UiSnapshot(
    val id: String,
    val sequence: Long = 0L,
    val capturedAtMs: Long,
    val packageName: String,
    val windowTitle: String,
    val rotation: Int,
    val displayBounds: Rect,
    val rootNode: UiNode?,
    val stateFingerprint: String = UiSnapshotFingerprint.compute(
        packageName = packageName,
        windowTitle = windowTitle,
        rotation = rotation,
        rootNode = rootNode
    )
)

/**
 * Stable state identity used to distinguish a real UI mutation from duplicate
 * accessibility events. This is intentionally cheaper than allocating JSON or
 * hashing a serialized hierarchy on the hot path.
 */
object UiSnapshotFingerprint {
    fun compute(
        packageName: String,
        windowTitle: String,
        rotation: Int,
        rootNode: UiNode?
    ): String {
        var hash = FNV_OFFSET_BASIS

        fun add(value: String) {
            value.forEach { character ->
                hash = hash xor character.code.toLong()
                hash *= FNV_PRIME
            }
            hash = hash xor FIELD_SEPARATOR
            hash *= FNV_PRIME
        }

        fun add(value: Boolean) = add(if (value) "1" else "0")

        fun visit(node: UiNode) {
            add(node.treePath)
            add(node.packageName)
            add(node.resourceId)
            add(node.className)
            add(node.text)
            add(node.contentDescription)
            add(node.isCheckable)
            add(node.isChecked)
            add(node.isClickable)
            add(node.isEnabled)
            add(node.isEditable)
            add(node.isFocusable)
            add(node.isFocused)
            add(node.isVisibleToUser)
            add(node.isScrollable)
            add(node.isLongClickable)
            add(node.isPassword)
            add(node.isSelected)
            add(node.boundsInScreen.left.toString())
            add(node.boundsInScreen.top.toString())
            add(node.boundsInScreen.right.toString())
            add(node.boundsInScreen.bottom.toString())
            node.children.forEach(::visit)
        }

        add(packageName)
        add(windowTitle)
        add(rotation.toString())
        rootNode?.let(::visit)
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
    private const val FIELD_SEPARATOR = 0xffL
}
