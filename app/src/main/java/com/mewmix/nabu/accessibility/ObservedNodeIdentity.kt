package com.mewmix.nabu.accessibility

import java.security.MessageDigest

/** Stable identity for one immutable observed node; never contains a live node reference. */
object ObservedNodeIdentity {
    fun compute(
        packageName: String?,
        resourceId: String?,
        text: String?,
        contentDescription: String?,
        className: String?,
        left: Int?,
        top: Int?,
        right: Int?,
        bottom: Int?,
        treePath: String
    ): String {
        val bounds = if (listOf(left, top, right, bottom).all { it != null }) {
            "$left,$top,$right,$bottom"
        } else {
            ""
        }
        val canonical = listOf(
            packageName,
            resourceId,
            text,
            contentDescription,
            className,
            bounds,
            treePath
        ).joinToString("\u001f") { it.orEmpty() }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return "e_" + digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
