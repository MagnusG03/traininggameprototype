package no.ntnu.traininggame

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
    }

    // Camera and detection
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var poseLandmarker: PoseLandmarker

    // UI
    private lateinit var overlayView: OverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayView = findViewById(R.id.overlayView)

        // Create a thread pool for image analysis
        val maxThreads = Runtime.getRuntime().availableProcessors()
        val poolSize = (maxThreads / 2).coerceAtLeast(2)
        Log.d(TAG, "Available CPU cores: $maxThreads -> Using pool size: $poolSize")
        cameraExecutor = Executors.newFixedThreadPool(poolSize)

        checkCameraPermission()
    }

    /**
     * Checks if CAMERA permission is granted. If not, requests it. Otherwise,
     * proceeds to initialize the Pose Landmarker and set up the camera.
     */
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Camera permission already granted.")
            initializePoseLandmarkerAndCamera()
        } else {
            Log.d(TAG, "Requesting camera permission...")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * Handles the result of a camera permission request.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Camera permission granted by user.")
            initializePoseLandmarkerAndCamera()
        } else {
            Log.e(TAG, "Camera permission denied.")
            finishAffinity()
        }
    }

    /**
     * Sets up the Pose Landmarker, then configures and starts the camera.
     */
    private fun initializePoseLandmarkerAndCamera() {
        initializePoseLandmarker()

        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            startCamera(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Creates a PoseLandmarker in LIVE_STREAM mode and configures its options.
     */
    private fun initializePoseLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("models/pose_landmarker_lite.task")
            .setDelegate(Delegate.GPU) // GPU delegation for faster inference
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            // Handle results asynchronously for each frame
            .setResultListener { result: PoseLandmarkerResult, _: MPImage ->
                if (result.landmarks().isNotEmpty()) {
                    // Visualize only the first pose detected
                    val poseLandmarks = result.landmarks()[0]
                    val landmarkPairs = poseLandmarks.map { lm ->
                        // Mirror x-coordinates for front camera
                        Pair(1.0f - lm.x(), lm.y())
                    }
                    overlayView.setLandmarks(landmarkPairs)
                    Log.d(TAG, "First landmark -> x=${poseLandmarks[0].x()}, y=${poseLandmarks[0].y()}")
                } else {
                    overlayView.setLandmarks(emptyList())
                    Log.v(TAG, "No pose detected on this frame.")
                }
            }
            .setErrorListener { error ->
                Log.e(TAG, "PoseLandmarker error: ${error.message}")
            }
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(this, options)
        Log.d(TAG, "PoseLandmarker initialized.")
    }

    /**
     * Configures CameraX with a preview and image analysis, then binds them to lifecycle.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun startCamera(cameraProvider: ProcessCameraProvider) {
        Log.d(TAG, "Starting camera...")

        val previewView = findViewById<PreviewView>(R.id.previewView)
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val resolutionStrategy = ResolutionStrategy(
            android.util.Size(320, 240),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
        )

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(resolutionStrategy).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val bitmap = YuvToRgbConverter.imageToBitmap(imageProxy)
                val rotatedBitmap = rotateBitmapIfNeeded(bitmap, rotationDegrees)

                // Create an MPImage for inference
                val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                poseLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Analysis error: ", e)
            } finally {
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
            Log.d(TAG, "Camera use cases bound successfully.")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     * Rotates a Bitmap if needed, based on its rotation in degrees.
     */
    private fun rotateBitmapIfNeeded(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        return Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true
        ).also {
            source.recycle()
        }
    }

    /**
     * Shut down executors and release the PoseLandmarker when the Activity is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseLandmarker.close()
        Log.d(TAG, "onDestroy: Executor and PoseLandmarker closed.")
    }
}
