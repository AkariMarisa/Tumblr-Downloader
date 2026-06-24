package com.example.tumblrdownloader.utils

import com.example.tumblrdownloader.model.MediaType

object TumblrParser {

    private val shareUrlRegex = Regex(
        "(?i)^https?://(?:www\\.)?(?:[a-z0-9-]+\\.)?tumblr\\.com/.+"
    )

    fun isTumblrShareUrl(text: String): Boolean {
        val trimmed = text.trim()
        return shareUrlRegex.containsMatchIn(trimmed)
    }

    fun firstTumblrUrl(text: String): String? {
        return shareUrlRegex.find(text.trim())?.value
    }

    fun parseMediaCandidates(postUrl: String): List<String> {
        // Placeholder parser: keep for now, return a deterministic candidate list.
        // Replace with real API/HTML parsing once network layer is wired.
        return if (isTumblrShareUrl(postUrl)) {
            listOf("$postUrl/image")
        } else {
            emptyList()
        }
    }

    fun guessType(mediaUrl: String): MediaType {
        val lower = mediaUrl.lowercase()
        return when {
            lower.contains(".mp4") || lower.contains(".mov") || lower.contains(".m3u8") -> MediaType.VIDEO
            lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp") -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
    }
}
