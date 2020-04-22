package com.worldsnas.textrecognizer

import android.os.Parcelable
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import kotlinx.android.parcel.Parcelize

@Parcelize
@Suppress("ArrayInDataClass")
data class CaptureLocalModel(
    val imageAddress : String
) : Parcelable {

    companion object {
        const val EXTRA_CAPTURE_MODEL = "EXTRA_CAPTURE_MODEL"
    }
}