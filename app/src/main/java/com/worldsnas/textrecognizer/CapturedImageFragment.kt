package com.worldsnas.textrecognizer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.worldsnas.textrecognizer.CaptureLocalModel.Companion.EXTRA_CAPTURE_MODEL
import kotlinx.android.synthetic.main.fragment_captured_image.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class CapturedImageFragment : Fragment(R.layout.fragment_captured_image) {

    lateinit var capturedBitmap: Bitmap

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val capture = arguments!!.getParcelable<CaptureLocalModel>(EXTRA_CAPTURE_MODEL)!!

        //we have to get the rotation for this image
        //i think 0 will do too
        //let's try the byteArray again
        capturedBitmap = BitmapFactory.decodeFile(capture.imageAddress)
        imgCaptured.setImageBitmap(capturedBitmap)

        view.findViewById<ImageButton>(R.id.button_second).setOnClickListener {
            findNavController().popBackStack()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val visionText = TextDetector.processImage(capturedBitmap.createVisionImage())
                withContext(Dispatchers.Main) {
                    BlocksRenderer.drawBlocks(
                        capturedBlockContainer,
                        Size(capturedBitmap.width, capturedBitmap.height),
                        BlocksRenderer.getImageRotation(imageAddress = capture.imageAddress),
                        visionText.textBlocks.toMutableList()
                    ) {
                        Toast.makeText(
                            requireContext(),
                            "selectedBlock=${it.text}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        capturedBitmap.recycle()
    }
}