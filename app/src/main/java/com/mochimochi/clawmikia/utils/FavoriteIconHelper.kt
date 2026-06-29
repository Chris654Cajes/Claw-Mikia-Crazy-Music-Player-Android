package com.mochimochi.clawmikiacrazy.utils

import com.mochimochi.clawmikiacrazy.R

/**
 * Maps a string icon type to the correct filled / outline drawable resource IDs
 * used for the favorite toggle button throughout the app.
 */
object FavoriteIconHelper {

    /** All available icon type keys. */
    val ALL_TYPES: List<String> = listOf(
        "heart", "smiley", "star", "eye", "sun",
        "flower", "moon", "music", "sparkles", "cloud"
    )

    /** Returns the "active / filled" drawable for the given icon type. */
    fun filledRes(iconType: String): Int = when (iconType) {
        "heart" -> R.drawable.ic_heart_filled
        "smiley" -> R.drawable.ic_smiley
        "star" -> R.drawable.ic_star
        "eye" -> R.drawable.ic_eye
        "sun" -> R.drawable.ic_sun
        "flower" -> R.drawable.ic_flower
        "moon" -> R.drawable.ic_moon
        "music" -> R.drawable.ic_music_note
        "sparkles" -> R.drawable.ic_sparkles
        "cloud" -> R.drawable.ic_cloud
        else -> R.drawable.ic_heart_filled
    }

    /** Returns the "inactive / outline" drawable for the given icon type. */
    fun outlineRes(iconType: String): Int = when (iconType) {
        "heart" -> R.drawable.ic_heart_outline
        "smiley" -> R.drawable.ic_smiley_outline
        "star" -> R.drawable.ic_star_outline
        "eye" -> R.drawable.ic_eye_outline
        "sun" -> R.drawable.ic_sun_outline
        "flower" -> R.drawable.ic_flower_outline
        "moon" -> R.drawable.ic_moon_outline
        "music" -> R.drawable.ic_music_note_outline
        "sparkles" -> R.drawable.ic_sparkles_outline
        "cloud" -> R.drawable.ic_cloud_outline
        else -> R.drawable.ic_heart_outline
    }

    /** Returns a unique color resource for each icon type. */
    fun colorRes(iconType: String): Int = when (iconType) {
        "heart" -> R.color.neon_pink
        "smiley" -> R.color.neon_yellow
        "star" -> R.color.neon_orange
        "eye" -> R.color.neon_cyan
        "sun" -> R.color.neon_green
        "flower" -> R.color.neon_purple
        "moon" -> R.color.neon_blue
        "music" -> R.color.neon_light_blue
        "sparkles" -> R.color.neon_green_alt
        "cloud" -> R.color.neon_red
        else -> R.color.neon_pink
    }

    /** Label shown in settings for each icon type. */
    fun label(iconType: String): String = iconType.replaceFirstChar { it.uppercase() }
}
