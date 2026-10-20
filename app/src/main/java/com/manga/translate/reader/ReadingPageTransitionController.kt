package com.manga.translate.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.TypedValue
import android.view.View
import androidx.core.view.isGone
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.model.ReadingPageAnimationMode

/** Animations belong to a view lifetime, never to the retained reading session. */
internal class ReadingPageTransitionController(
    private val binding: FragmentReadingBinding,
    private val animationMode: () -> ReadingPageAnimationMode,
    private val onCompleted: () -> Unit
) {
    private var released = false
    private var pageTransitionGeneration = 0
    private val pageTransitionInterpolator = FastOutSlowInInterpolator()
    private val incomingPageParallaxDp = 28f

    fun release() {
        cancel()
        finish()
        released = true
        binding.readingTransitionImage.setImageDrawable(null)
    }

    fun start(previousSnapshot: Bitmap, direction: Int) {
        if (animationMode() != ReadingPageAnimationMode.HORIZONTAL_SLIDE) {
            finish()
            return
        }
        val width = binding.readingContentContainer.width
        if (width <= 0) {
            finish()
            return
        }
        cancel()
        val generation = ++pageTransitionGeneration
        val parallaxOffset = resolveIncomingPageParallaxOffset(direction)
        binding.readingTransitionImage.setImageBitmap(previousSnapshot)
        binding.readingTransitionImage.scaleType = android.widget.ImageView.ScaleType.FIT_XY
        binding.readingTransitionImage.imageMatrix = Matrix()
        binding.readingTransitionImage.visibility = View.VISIBLE
        binding.readingTransitionImage.translationX = 0f
        binding.readingTransitionImage.alpha = 1f
        binding.translationOverlay.visibility = View.INVISIBLE
        binding.readingImage.translationX = parallaxOffset
        binding.readingImage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.readingTransitionImage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.readingImage.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(pageTransitionInterpolator)
            .setListener(null)
            .start()
        binding.readingTransitionImage.animate()
            .translationX(direction * width.toFloat())
            .setDuration(260L)
            .setInterpolator(pageTransitionInterpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completePageTransition(generation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    completePageTransition(generation)
                }
            })
            .start()
    }

    private fun completePageTransition(generation: Int) {
        if (released || generation != pageTransitionGeneration) return
        finish()
        onCompleted()
    }

    fun cancel() {
        pageTransitionGeneration += 1
        if (released) return
        binding.readingImage.animate().cancel()
        binding.readingTransitionImage.animate().cancel()
    }

    fun finish() {
        if (released) return
        binding.readingImage.animate().setListener(null)
        binding.readingTransitionImage.animate().setListener(null)
        binding.readingImage.translationX = 0f
        binding.readingImage.alpha = 1f
        binding.readingTransitionImage.translationX = 0f
        binding.readingTransitionImage.alpha = 1f
        binding.readingTransitionImage.visibility = View.GONE
        binding.readingImage.setLayerType(View.LAYER_TYPE_NONE, null)
        binding.readingTransitionImage.setLayerType(View.LAYER_TYPE_NONE, null)
        binding.readingTransitionImage.post {
            if (released) return@post
            if (binding.readingTransitionImage.isGone) {
                binding.readingTransitionImage.setImageDrawable(null)
            }
        }
    }

    private fun resolveIncomingPageParallaxOffset(direction: Int): Float {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            incomingPageParallaxDp,
            binding.root.resources.displayMetrics
        )
        return (-direction) * px
    }


}
