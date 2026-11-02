package app.foctuner.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/** False when motion is turned off (Display › Motion); screens, pages and charts then change in place. */
val LocalMotion = staticCompositionLocalOf { true }

/**
 * Durations and curves for everything that moves. Emphasized easing starts quickly and settles slowly, so a
 * change reads at a glance without the screen feeling slow.
 */
object Motion {
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 420
}

/**
 * One level deeper (list to page) when [forward], back out otherwise. The upper page slides the full width
 * while the one beneath shifts a quarter and fades, so depth reads as parallax.
 */
fun <S> AnimatedContentTransitionScope<S>.push(forward: Boolean): ContentTransform {
    val slide = tween<IntOffset>(Motion.LONG, easing = Motion.emphasized)
    val fade = tween<Float>(Motion.LONG, easing = LinearEasing)
    return if (forward) {
        slideInHorizontally(slide) { it }
            .togetherWith(slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade))
            .apply { targetContentZIndex = 1f }
    } else {
        (slideInHorizontally(slide) { -it / 4 } + fadeIn(fade))
            .togetherWith(slideOutHorizontally(slide) { it })
            .apply { targetContentZIndex = -1f }
    }
}

/** Between siblings (tabs, destinations): a short slide toward [direction] (+1 right, −1 left) with a cross-fade. */
fun <S> AnimatedContentTransitionScope<S>.lateral(direction: Int): ContentTransform {
    val slide = tween<IntOffset>(Motion.MEDIUM + 60, easing = Motion.emphasized)
    return (slideInHorizontally(slide) { direction * it / 5 } + fadeIn(tween(Motion.MEDIUM, delayMillis = 60)))
        .togetherWith(slideOutHorizontally(slide) { -direction * it / 5 } + fadeOut(tween(Motion.SHORT)))
}

/** Unrelated content replacing each other (connect screen and connected shell): out first, then in. */
fun <S> AnimatedContentTransitionScope<S>.fadeThrough(): ContentTransform =
    (fadeIn(tween(Motion.MEDIUM, delayMillis = 90)) + scaleIn(tween(Motion.MEDIUM, delayMillis = 90, easing = Motion.emphasized), initialScale = 0.96f))
        .togetherWith(fadeOut(tween(90)))

/** For small state changes (a color, an indicator's position): a short tween with motion on, a snap with it off. */
@Composable
fun <T> motionSpec(durationMillis: Int = Motion.MEDIUM): FiniteAnimationSpec<T> =
    if (LocalMotion.current) tween(durationMillis, easing = Motion.emphasized) else snap()

/** [AnimatedContent] that uses [transition] while motion is on and swaps in place while it is off. */
@Composable
fun <S> FocAnimatedContent(
    target: S,
    label: String,
    modifier: Modifier = Modifier,
    contentKey: (S) -> Any? = { it },
    transition: AnimatedContentTransitionScope<S>.() -> ContentTransform,
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val motion = LocalMotion.current
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = { if (motion) transition() else EnterTransition.None togetherWith ExitTransition.None },
        label = label,
        contentKey = contentKey,
        content = content,
    )
}
