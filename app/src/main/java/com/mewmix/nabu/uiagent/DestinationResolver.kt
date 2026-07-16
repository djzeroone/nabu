package com.mewmix.nabu.uiagent

import java.text.Normalizer

/**
 * Deterministic, package-specific resolver for messaging destination identity.
 * Uses accessibility resource IDs to locate the chat title—never LLM output.
 *
 * Only known messaging packages are supported. Unknown packages return [Unresolvable].
 */
object DestinationResolver {

    sealed interface DestinationResult {
        /** The observed destination was proven and (if expected was supplied) matches. */
        data class Verified(val observed: String) : DestinationResult
        /** The observed destination differs from the expected destination. */
        data class Mismatch(val observed: String?, val expected: String) : DestinationResult
        /** The destination could not be proven from the accessibility tree. */
        data class Unresolvable(val reason: String) : DestinationResult
    }

    /**
     * Resolve the current chat destination from the accessibility tree.
     *
     * @param screen The current screen state from the accessibility service.
     * @param expectedDestination Trusted destination from the user's explicit request. The observed title
     *                            must match it (case-insensitive, NFC-normalized).
     * @return A [DestinationResult] indicating whether the destination was verified, mismatched, or unresolvable.
     */
    fun resolve(screen: UiScreenState, expectedDestination: String): DestinationResult {
        if (expectedDestination.isBlank()) {
            return DestinationResult.Unresolvable("Expected destination is blank.")
        }
        val pkg = screen.packageName
            ?: return DestinationResult.Unresolvable("Screen has no package name.")

        val observed = resolveForPackage(pkg, screen)
            ?: return DestinationResult.Unresolvable("No chat title element found for package '$pkg'.")

        if (!normalizedEquals(observed, expectedDestination)) {
            return DestinationResult.Mismatch(observed = observed, expected = expectedDestination)
        }

        return DestinationResult.Verified(observed = observed)
    }

    /** Returns true only for packages this resolver can deterministically prove. */
    fun isSupported(packageName: String?): Boolean = packageName in SUPPORTED_PACKAGES

    private fun resolveForPackage(pkg: String, screen: UiScreenState): String? = when (pkg) {
        "org.telegram.messenger" -> {
            // Telegram places the chat title in a TextView with this resource ID
            screen.elements.firstOrNull {
                it.resourceId == "org.telegram.messenger:id/title" && it.text?.isNotBlank() == true
            }?.text
        }
        "com.google.android.apps.messaging" -> {
            screen.elements.firstOrNull {
                it.resourceId == "com.google.android.apps.messaging:id/conversation_title" && it.text?.isNotBlank() == true
            }?.text
        }
        "com.samsung.android.messaging" -> {
            screen.elements.firstOrNull {
                it.resourceId == "com.samsung.android.messaging:id/conversation_title" && it.text?.isNotBlank() == true
            }?.text
        }
        else -> null // Unsupported package — cannot prove destination
    }

    private fun normalizedEquals(a: String, b: String): Boolean {
        val aDigits = a.filter(Char::isDigit)
        val bDigits = b.filter(Char::isDigit)
        if (aDigits.length >= 7 && bDigits.length >= 7) {
            return aDigits == bDigits || aDigits.takeLast(10) == bDigits.takeLast(10)
        }
        val na = Normalizer.normalize(a.trim(), Normalizer.Form.NFC)
        val nb = Normalizer.normalize(b.trim(), Normalizer.Form.NFC)
        return na.equals(nb, ignoreCase = true)
    }

    private val SUPPORTED_PACKAGES = setOf(
        "org.telegram.messenger",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )
}
