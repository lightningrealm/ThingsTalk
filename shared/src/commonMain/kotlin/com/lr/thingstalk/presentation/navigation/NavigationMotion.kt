package com.lr.thingstalk.presentation.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/** Shared motion for both platforms, independent of page layout and platform materials. */
internal object NavigationMotion {
    const val DurationMillis = 280

    fun forward(): ContentTransform =
        (fadeIn(tween(DurationMillis, easing = FastOutSlowInEasing)) +
            slideInHorizontally(tween(DurationMillis, easing = FastOutSlowInEasing)) { it / 12 }) togetherWith
            (fadeOut(tween(180)) +
                slideOutHorizontally(tween(DurationMillis, easing = FastOutSlowInEasing)) { -it / 24 })

    // NavDisplay seeks this same transform during predictive back, including gesture cancellation.
    fun back(): ContentTransform =
        (fadeIn(tween(DurationMillis, easing = FastOutSlowInEasing)) +
            slideInHorizontally(tween(DurationMillis, easing = FastOutSlowInEasing)) { -it / 24 }) togetherWith
            (fadeOut(tween(DurationMillis)) +
                slideOutHorizontally(tween(DurationMillis, easing = FastOutSlowInEasing)) { it / 12 })

    /** Switching peer tabs has no forward/back hierarchy. */
    fun crossfade(): ContentTransform = fadeIn(tween(180)) togetherWith fadeOut(tween(180))
}
