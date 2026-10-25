package app.brisa.browser

import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object Motion {
    val decelerate: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val accelerate: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    val standard: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    const val SHORT = 140L
    const val MEDIUM = 240L
}

/** Springy press feedback. Never consumes the touch, so clicks and ripples keep working. */
fun View.pressable(scale: Float = 0.86f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.springTo(scale)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.springTo(1f)
        }
        false
    }
}

private fun View.springTo(target: Float) {
    for (property in listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y)) {
        SpringAnimation(this, property, target).apply {
            spring.setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
            start()
        }
    }
}
