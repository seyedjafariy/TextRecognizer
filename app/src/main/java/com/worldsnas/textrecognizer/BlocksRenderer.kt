package com.worldsnas.textrecognizer

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.RectF
import android.net.Uri
import android.util.Size
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.toRectF
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.ml.vision.text.FirebaseVisionText
import kotlin.math.roundToInt

typealias BlockClickListener = (block: FirebaseVisionText.TextBlock) -> Unit

data class Ratio(
    val widthRatio: Float,
    val heightRatio: Float
)

data class Coordinate(
    val x: Float,
    val y: Float
)

object BlocksRenderer {

    private const val IMAGE_ROTATION_UP = 0
    private const val IMAGE_ROTATION_DOWN = 180
    private const val IMAGE_ROTATION_LAND_RIGHT = 90
    private const val IMAGE_ROTATION_LAND_LEFT = 270

    private const val BOX_ANIMATION_DURATION = 200L

    fun drawBlocks(
        blocksViewGroup: ViewGroup,
        imageSize: Size,
        imageRotation: Int,
        textBlocks: MutableList<FirebaseVisionText.TextBlock>,
        clicks: BlockClickListener
    ) {
        val parentViewSize = Size(
            blocksViewGroup.width,
            blocksViewGroup.height
        )
        //because the image rotation is 90/270 we have to rotate the height and width
        //we also have to multiply them by the scale to match the size of the view to the taken image
        val ratio = getRatio(parentViewSize, imageSize, imageRotation)

        for (view in blocksViewGroup.children.toList()) {
            view as ImageView

            val text = view.getTag(R.id.foundTextTag) as? String

            if (text == null) {
                blocksViewGroup.removeView(view)
                continue
            }

            val updatedBlock = textBlocks.find { it.text.replace(" ", "") == text.replace(" ", "") }

            if (updatedBlock == null) {
                blocksViewGroup.removeView(view)
            } else {
                view.setTag(R.id.foundBlockTag, updatedBlock)
                translateView(view, updatedBlock, imageRotation, ratio)
                textBlocks.remove(updatedBlock)
            }
        }

        textBlocks.forEach {
            val image = AppCompatImageView(blocksViewGroup.context).apply {
                setImageResource(R.drawable.background_rectangle)
                setTag(R.id.foundTextTag, it.text)
                setTag(R.id.foundBlockTag, it)
                setOnClickListener {
                    clicks(getTag(R.id.foundBlockTag) as FirebaseVisionText.TextBlock)
                }
            }

            val rect = it.boundingBox!!.toRectF()

            val coordinate = getViewCoordinate(rect, ratio, imageRotation)

            val viewSize = getViewSize(rect, ratio, imageRotation)

            image.x = coordinate.x
            image.y = coordinate.y

            blocksViewGroup.addView(
                image,
                viewSize.width,
                viewSize.height
            )
        }
    }

    private fun translateView(
        view: ImageView,
        newBlock: FirebaseVisionText.TextBlock,
        imageRotation: Int,
        ratio: Ratio
    ) {
        val oldAnimator = view.getTag(R.id.foundTextAnimationTag) as? AnimatorSet
        oldAnimator?.cancel()

        val rect = newBlock.boundingBox!!.toRectF()

        val viewSize = getViewSize(rect, ratio, imageRotation)
        val viewCoordinate = getViewCoordinate(rect, ratio, imageRotation)

        val xAnimator = ObjectAnimator.ofFloat(
            view,
            "translationX",
            view.x,
            viewCoordinate.x
        ).apply {
            duration = BOX_ANIMATION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
        }

        val yAnimator = ObjectAnimator.ofFloat(
            view,
            "translationY",
            view.y,
            viewCoordinate.y
        ).apply {
            duration = BOX_ANIMATION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
        }

        val widthAnimator = ValueAnimator.ofInt(view.width, viewSize.width).apply {
            addUpdateListener { valueAnimator ->
                view.updateLayoutParams {
                    width = valueAnimator.animatedValue as Int
                }
            }
            interpolator = AccelerateDecelerateInterpolator()
            duration = BOX_ANIMATION_DURATION
        }

        val heightAnimator = ValueAnimator.ofInt(view.height, viewSize.height).apply {
            addUpdateListener { valueAnimator ->
                view.updateLayoutParams {
                    height = valueAnimator.animatedValue as Int
                }
            }
            interpolator = AccelerateDecelerateInterpolator()
            duration = BOX_ANIMATION_DURATION
        }

        val animation = AnimatorSet().apply {
            playTogether(xAnimator, yAnimator, widthAnimator, heightAnimator)
        }

        animation.start()

        view.setTag(R.id.foundTextAnimationTag, animation)
    }

    private fun getRatio(parentSize: Size, imageSize: Size, imageRotation: Int): Ratio =
        if (imageRotation == IMAGE_ROTATION_UP || imageRotation == IMAGE_ROTATION_DOWN) {
            Ratio(
                parentSize.width.toFloat() / imageSize.width.toFloat(),
                parentSize.height.toFloat() / imageSize.height.toFloat()
            )
        } else {
            Ratio(
                parentSize.height.toFloat() / imageSize.width.toFloat(),
                parentSize.width.toFloat() / imageSize.height.toFloat()
            )
        }

    private fun getViewSize(boundingRect: RectF, ratio: Ratio, imageRotation: Int): Size =
        //90/270
        if (imageRotation == IMAGE_ROTATION_LAND_LEFT || imageRotation == IMAGE_ROTATION_LAND_RIGHT) {
            Size(
                (boundingRect.width() * ratio.heightRatio).roundToInt(),
                (boundingRect.height() * ratio.widthRatio).roundToInt()
            )
        } else {
            //0
            Size(
                (boundingRect.width() * ratio.widthRatio).roundToInt(),
                (boundingRect.height() * ratio.heightRatio).roundToInt()
            )
        }

    private fun getViewCoordinate(
        boundingRect: RectF,
        ratio: Ratio,
        imageRotation: Int
    ): Coordinate =
        if (imageRotation == IMAGE_ROTATION_LAND_LEFT || imageRotation == IMAGE_ROTATION_LAND_RIGHT) {
            Coordinate(
                x = boundingRect.left * ratio.heightRatio,
                y = boundingRect.top * ratio.widthRatio
            )
        } else {
            Coordinate(
                x = boundingRect.left * ratio.widthRatio,
                y = boundingRect.top * ratio.heightRatio
            )
        }

    fun getImageRotation(imageAddress : String) =
        ExifInterface(imageAddress).rotationDegrees
}