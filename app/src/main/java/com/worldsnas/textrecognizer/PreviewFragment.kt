package com.worldsnas.textrecognizer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.DISPLAY_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.worldsnas.textrecognizer.CaptureLocalModel.Companion.EXTRA_CAPTURE_MODEL
import kotlinx.android.synthetic.main.fragment_preview.*
import kotlinx.coroutines.*
import java.io.File
import java.lang.Runnable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val CAMERA_PERMISSION_REQUEST_CODE = 101
private val TAG = PreviewFragment::class.simpleName

private const val RATIO_4_3_VALUE = 4.0 / 3.0
private const val RATIO_16_9_VALUE = 16.0 / 9.0

private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
private const val PHOTO_EXTENSION = ".jpg"

private val FLASH_MAPPING = mapOf(
    R.drawable.ic_baseline_flash_auto_24 to FLASH_MODE_AUTO,
    R.drawable.ic_baseline_flash_off_24 to FLASH_MODE_OFF,
    R.drawable.ic_baseline_flash_on_24 to FLASH_MODE_ON
)

class PreviewFragment : Fragment(R.layout.fragment_preview) {

    private var displayId: Int = -1

    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService? = null
    private var dispatcher: CoroutineDispatcher? = null

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null

    private var screenAspectRatio: Int = 0
    private var rotation: Int = 0
    private var flashMode = FLASH_MODE_AUTO

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

        cameraSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            cameraLens = if (isChecked) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

            bindCameraUseCases()
        }

        val flashDrawableList = FLASH_MAPPING.toList()

        triStateFlashMode.states = Triple(
            flashDrawableList[0].first,
            flashDrawableList[1].first,
            flashDrawableList[2].first
        )
        triStateFlashMode.clickListener = { _, currentState, states ->
            flashMode = FLASH_MAPPING[currentState]!!

            imageCapture?.flashMode = flashMode
        }
        fabCapture.setOnClickListener {
            captureAndSaveImage()
        }

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

            imageCapture = buildImageCapture(rotation)

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

    private fun buildImageCapture(rotation: Int): ImageCapture {
        return Builder().apply {
            setCaptureMode(CAPTURE_MODE_MAXIMIZE_QUALITY)
            setTargetAspectRatio(screenAspectRatio)
            setTargetRotation(rotation)
            setFlashMode(flashMode)
        }.build()
    }

    private fun captureAndSaveImage() {
        val photoFile = createFile(getOutputDirectory(requireContext()), FILENAME, PHOTO_EXTENSION)

        // Setup image capture metadata
        val metadata = ImageCapture.Metadata().apply {

            // Mirror image when using the front camera
            isReversedHorizontal = cameraLens == CameraSelector.LENS_FACING_FRONT
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
            .setMetadata(metadata)
            .build()

        // Setup image capture listener which is triggered after photo has been taken
        imageCapture!!.takePicture(
            outputOptions, cameraExecutor!!, object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                    Log.d(TAG, "Photo capture succeeded: $savedUri")

                    // Implicit broadcasts will be ignored for devices running API level >= 24
                    // so if you only target API level 24+ you can remove this statement
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                        requireActivity().sendBroadcast(
                            Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                        )
                    }

                    // If the folder selected is an external media directory, this is
                    // unnecessary but otherwise other apps will not be able to access our
                    // images unless we scan them using [MediaScannerConnection]
                    val mimeType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(savedUri.toFile().extension)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(savedUri.toString()),
                        arrayOf(mimeType)
                    ) { _, uri ->
                        Log.d(TAG, "Image capture scanned into media store: $uri")
                    }
                    val model = CaptureLocalModel(
                        savedUri.path!!
                    )
                    openCapturePreviewFragment(model)
                }
            })
    }

    private fun getOutputDirectory(context: Context): File {
        val appContext = context.applicationContext
        val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
            File(it, appContext.resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else appContext.filesDir
    }

    @Suppress("SameParameterValue")
    private fun createFile(baseFolder: File, format: String, extension: String) =
        File(
            baseFolder, SimpleDateFormat(format, Locale.US)
                .format(System.currentTimeMillis()) + extension
        )

    private fun openCapturePreviewFragment(model: CaptureLocalModel) {
        findNavController().navigate(
            R.id.action_PreviewFragment_to_CapturedImageFragment,
            bundleOf(
                EXTRA_CAPTURE_MODEL to model
            )
        )
    }
}