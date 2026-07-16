package com.mewmix.nabu.uiagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Manages temporary media output URIs for camera capture and media sharing.
 * All URIs are created through FileProvider and tracked for cleanup.
 */
object AutomationMediaManager {

    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".automation.fileprovider"
    private const val CAPTURE_DIR = "automation_capture"

    /**
     * Creates a temporary file via FileProvider and returns a content URI for camera output.
     *
     * @param context Application context
     * @param mimeType MIME type of the expected capture (e.g., "image/jpeg", "video/mp4")
     * @return Content URI suitable for use as EXTRA_OUTPUT in a camera intent
     */
    fun createCaptureOutputUri(context: Context, mimeType: String): Uri {
        val extension = when (mimeType) {
            IMAGE_MIME_TYPE -> ".jpg"
            VIDEO_MIME_TYPE -> ".mp4"
            else -> throw IllegalArgumentException("Unsupported automation capture MIME type: $mimeType")
        }
        val fileName = "nabu_capture_${UUID.randomUUID()}$extension"

        val captureDir = File(context.cacheDir, CAPTURE_DIR)
        check(captureDir.exists() || captureDir.mkdirs()) { "Unable to create automation capture directory." }

        val captureFile = File(captureDir, fileName)
        check(captureFile.createNewFile()) { "Unable to create automation capture file." }

        val authority = "${com.mewmix.nabu.BuildConfig.APPLICATION_ID}$FILE_PROVIDER_AUTHORITY_SUFFIX"
        return FileProvider.getUriForFile(context, authority, captureFile)
    }

    /**
     * Validates that the capture output URI points to a non-empty file.
     *
     * @param context Application context
     * @param uri Content URI to validate
     * @return true if the backing file exists and has content
     */
    fun validateCaptureOutput(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) true else {
                    context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } == true
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun contentSha256(context: Context, uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            stream.use {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun contentSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0L }
        }
    }.getOrNull()

    /**
     * Grants read permission on the URI to a specific package.
     *
     * @param context Application context
     * @param uri Content URI to grant
     * @param targetPackage Package name to grant read access to
     */
    fun grantUriReadPermission(context: Context, uri: Uri, targetPackage: String) {
        context.grantUriPermission(
            targetPackage,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    /**
     * Revokes all URI permissions previously granted for this URI.
     *
     * @param context Application context
     * @param uri Content URI to revoke permissions on
     */
    fun revokeUriPermissions(context: Context, uri: Uri) {
        try {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {
            // Best-effort; URI may already be revoked or invalid
        }
    }

    /**
     * Deletes the backing file for a temporary capture URI.
     *
     * @param context Application context
     * @param uri Content URI whose backing file should be deleted
     */
    fun deleteTemporaryMedia(context: Context, uri: Uri) {
        val deletedThroughProvider = runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
        if (!deletedThroughProvider) {
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: return
            runCatching {
                val captureDir = File(context.cacheDir, CAPTURE_DIR)
                val file = File(captureDir, fileName)
                if (file.canonicalFile.parentFile == captureDir.canonicalFile && file.exists()) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Revokes permissions and deletes all supplied URIs.
     * Idempotent: calling twice with the same URIs does not throw.
     * Intended to be called from the orchestrator's finally block.
     *
     * @param context Application context
     * @param uris Collection of content URIs to clean up
     */
    fun cleanupAll(context: Context, uris: Collection<Uri>) {
        for (uri in uris) {
            revokeUriPermissions(context, uri)
            deleteTemporaryMedia(context, uri)
        }
    }

    /**
     * Deletes the entire capture directory contents.
     * Used for full cleanup on app start or session end.
     *
     * @param context Application context
     */
    fun purgeAllCaptures(context: Context) {
        val captureDir = File(context.cacheDir, CAPTURE_DIR)
        if (captureDir.exists()) {
            captureDir.listFiles()?.forEach { it.delete() }
        }
    }

    const val IMAGE_MIME_TYPE = "image/jpeg"
    const val VIDEO_MIME_TYPE = "video/mp4"
}
