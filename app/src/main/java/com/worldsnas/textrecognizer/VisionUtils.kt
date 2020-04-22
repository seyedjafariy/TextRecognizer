package com.worldsnas.textrecognizer

import android.annotation.SuppressLint
import androidx.camera.core.ImageProxy
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata

@SuppressLint("UnsafeExperimentalUsageError")
fun ImageProxy.createVisionImageFromMedia(): FirebaseVisionImage =
    FirebaseVisionImage.fromMediaImage(
        image!!,
        getFirebaseRotation(imageInfo.rotationDegrees)
    )

fun getFirebaseRotation(rotationCompensation: Int): Int =
    when (rotationCompensation) {
        0 -> FirebaseVisionImageMetadata.ROTATION_0
        90 -> FirebaseVisionImageMetadata.ROTATION_90
        180 -> FirebaseVisionImageMetadata.ROTATION_180
        270 -> FirebaseVisionImageMetadata.ROTATION_270
        else -> {
            FirebaseVisionImageMetadata.ROTATION_0
        }
    }