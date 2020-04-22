package com.worldsnas.textrecognizer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context.DISPLAY_SERVICE
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import kotlinx.android.synthetic.main.fragment_preview.*
import kotlinx.coroutines.*
import java.lang.Runnable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val CAMERA_PERMISSION_REQUEST_CODE = 101
private val TAG = PreviewFragment::class.simpleName

private const val RATIO_4_3_VALUE = 4.0 / 3.0
private const val RATIO_16_9_VALUE = 16.0 / 9.0

class PreviewFragment : Fragment(R.layout.fragment_preview) {

    private var displayId: Int = -1

    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService? = null
    private var dispatcher: CoroutineDispatcher? = null

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null

    private var screenAspectRatio: Int = 0
    private var rotation: Int = 0

    private var cameraLens = CameraSelector.LENS_FACING_BACK

    private val displayManager by lazy {
        requireContext().getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@PreviewFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    private val blockListener: BlockClickListener = {
        Toast.makeText(
            requireContext(),
            "chose the block with text= ${it.text}",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        dispatcher = cameraExecutor!!.asCoroutineDispatcher()

        previewCamera.post {
            displayId = previewCamera.display.displayId
        }
        displayManager.registerDisplayListener(displayListener, null)
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermissions()) {
            previewCamera.post {
                bindCameraUseCases()
            }
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        displayManager.unregisterDisplayListener(displayListener)
        dispatcher?.cancel()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        imageAnalyzer?.clearAnalyzer()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                previewCamera.post {
                    bindCameraUseCases()
                }
            }
        }
    }

    private fun hasCameraPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun bindCameraUseCases() {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { previewCamera.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        rotation = previewCamera.display.rotation

        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = buildCameraSelector()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            preview = buildPreview()

            // ImageAnalysis
            imageAnalyzer = buildImageAnalysis()

            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(previewCamera.createSurfaceProvider(camera?.cameraInfo))

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun buildCameraSelector() =
        CameraSelector.Builder().requireLensFacing(cameraLens).build()

    private fun buildPreview(): Preview {
        return Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build()
    }

    private fun buildImageAnalysis() =
        ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()
            // The analyzer can then be assigned to the instance
            .also {
                it.setAnalyzer(cameraExecutor!!, ImageAnalysis.Analyzer {
                    extractText(it)
                })
            }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun extractText(image: ImageProxy) {
        if (image.image == null) {
            return
        }

        runBlocking {
            try {
                val textImage = image.createVisionImageFromMedia()

                val visionText = TextDetector.processImage(textImage)

                val blocks = visionText.textBlocks.toMutableList()

                withContext(Dispatchers.Main) {
                    BlocksRenderer.drawBlocks(
                        blocksContainer,
                        Size(image.width, image.height),
                        image.imageInfo.rotationDegrees,
                        blocks,
                        blockListener
                    )
                }

                Log.d("image recognizer", "reading image success= $blocks")

            } catch (e: Exception) {
                e.printStackTrace()
            }
            image.close()
        }
    }
}