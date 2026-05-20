package com.openclaw.assistant.ui.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.service.AssistantState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Morphing sphere" voice indicator — organic blob that
 * breathes while idle, ripples in response to live microphone amplitude while
 * listening, swirls slowly while thinking, and pulses while the assistant
 * speaks. Drives off [AssistantState] + an `audioLevel` already normalised
 * by the caller into a "loudness 0..1" target.
 *
 * Implementation:
 *  - Radius at each sampled angle θ is computed as
 *      `R(θ) = R₀ · (1 + Σₖ aₖ · sin(kθ + φₖ))`
 *    where φₖ advances on infinite transitions and aₖ scale with audio.
 *  - Two stacked phases (slow + fast) create the morphing feel without
 *    looking jittery.
 *  - A radial gradient + glow ring give it presence on dark and light
 *    overlay backgrounds.
 */
@Composable
fun MorphingSphere(
    state: AssistantState,
    audioLevel: Float,
    modifier: Modifier = Modifier,
) {
    val phaseTransition = rememberInfiniteTransition(label = "sphere_phase")
    // Two phases at incommensurate periods produce the "alive" morphing.
    val phaseSlow by phaseTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = animationPeriodMs(state).first, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase_slow",
    )
    val phaseFast by phaseTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = animationPeriodMs(state).second, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase_fast",
    )

    // Audio-reactive bump amplitude — clamped to 0..1 (caller already
    // normalises). Smooth into a spring so loud peaks don't cause hard pops.
    val targetAudio = audioLevel.coerceIn(0f, 1f) * audioWeight(state)
    val audioSmooth by animateFloatAsState(
        targetValue = targetAudio,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "audio_smooth",
    )

    // State color, animated so transitions don't snap.
    val target = stateColor(state)
    val r = remember { Animatable(target.red) }
    val g = remember { Animatable(target.green) }
    val b = remember { Animatable(target.blue) }
    LaunchedEffect(target) {
        r.animateTo(target.red, animationSpec = tween(400))
        g.animateTo(target.green, animationSpec = tween(400))
        b.animateTo(target.blue, animationSpec = tween(400))
    }
    val core = Color(r.value, g.value, b.value)

    Box(modifier = modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(120.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = min(size.width, size.height) / 2f * 0.78f

            val (kSlow, ampSlow) = harmonicsSlow(state)
            val (kFast, ampFast) = harmonicsFast(state, audioSmooth)

            val path = Path()
            val samples = 96
            for (i in 0..samples) {
                val theta = (i.toFloat() / samples.toFloat()) * 2f * PI.toFloat()
                val r1 = ampSlow * sin(kSlow * theta + phaseSlow)
                val r2 = ampFast * sin(kFast * theta + phaseFast)
                val r3 = 0.05f * audioSmooth * cos(7f * theta - phaseFast * 1.7f)
                val radius = baseRadius * (1f + r1 + r2 + r3)
                val px = center.x + radius * cos(theta)
                val py = center.y + radius * sin(theta)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()

            // Outer glow halo (audio-reactive)
            val glowRadius = baseRadius * (1.15f + 0.25f * audioSmooth)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(core.copy(alpha = 0.35f), core.copy(alpha = 0f)),
                    center = center,
                    radius = glowRadius,
                ),
                center = center,
                radius = glowRadius,
            )

            // Core fill — radial gradient from white-tinted highlight to the
            // state colour at the edge.
            drawPath(
                path = path,
                brush = Brush.radialGradient(
                    colors = listOf(core.copy(alpha = 0.95f), core.copy(alpha = 0.65f), core.copy(alpha = 0.35f)),
                    center = center.copy(x = center.x - baseRadius * 0.2f, y = center.y - baseRadius * 0.25f),
                    radius = baseRadius * 1.4f,
                ),
            )

            // Rim stroke for definition
            drawPath(
                path = path,
                brush = Brush.linearGradient(colors = listOf(core, core.copy(alpha = 0.4f))),
                style = Stroke(width = 1.5f),
            )
        }
    }
}

/** Pair (slowPeriodMs, fastPeriodMs) — slower while thinking, faster while listening. */
internal fun animationPeriodMs(state: AssistantState): Pair<Int, Int> = when (state) {
    AssistantState.IDLE -> 6000 to 3500
    AssistantState.LISTENING -> 3500 to 1400
    AssistantState.PROCESSING, AssistantState.THINKING -> 9000 to 4500
    AssistantState.PREPARING_SPEECH, AssistantState.SPEAKING -> 4000 to 1800
    AssistantState.ERROR -> 2500 to 800
}

/** Audio-level weight per state — speaking/listening lean on mic, idle ignores it. */
internal fun audioWeight(state: AssistantState): Float = when (state) {
    AssistantState.LISTENING -> 1f
    AssistantState.SPEAKING, AssistantState.PREPARING_SPEECH -> 0.7f
    AssistantState.THINKING, AssistantState.PROCESSING -> 0.2f
    else -> 0.0f
}

/** Slow harmonic (low-frequency lobes that give the blob shape). */
internal fun harmonicsSlow(state: AssistantState): Pair<Float, Float> = when (state) {
    AssistantState.IDLE -> 2f to 0.045f
    AssistantState.LISTENING -> 3f to 0.06f
    AssistantState.PROCESSING, AssistantState.THINKING -> 2f to 0.09f
    AssistantState.PREPARING_SPEECH, AssistantState.SPEAKING -> 3f to 0.07f
    AssistantState.ERROR -> 5f to 0.12f
}

/** Fast harmonic — bigger when audio is louder. */
internal fun harmonicsFast(state: AssistantState, audio: Float): Pair<Float, Float> {
    val k = when (state) {
        AssistantState.IDLE -> 5f
        AssistantState.LISTENING -> 6f
        AssistantState.PROCESSING, AssistantState.THINKING -> 4f
        AssistantState.PREPARING_SPEECH, AssistantState.SPEAKING -> 5f
        AssistantState.ERROR -> 8f
    }
    val baseAmp = when (state) {
        AssistantState.IDLE -> 0.018f
        AssistantState.LISTENING -> 0.035f
        AssistantState.THINKING, AssistantState.PROCESSING -> 0.04f
        AssistantState.PREPARING_SPEECH, AssistantState.SPEAKING -> 0.05f
        AssistantState.ERROR -> 0.08f
    }
    val amp = baseAmp + 0.13f * audio
    return k to amp
}

internal fun stateColor(state: AssistantState): Color = when (state) {
    AssistantState.IDLE -> Color(0xFF9AA0A6)
    AssistantState.LISTENING -> Color(0xFF34D399)
    AssistantState.PROCESSING, AssistantState.THINKING -> Color(0xFFFBBF24)
    AssistantState.PREPARING_SPEECH, AssistantState.SPEAKING -> Color(0xFF60A5FA)
    AssistantState.ERROR -> Color(0xFFEF4444)
}
