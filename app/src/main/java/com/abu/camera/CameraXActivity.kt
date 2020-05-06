package com.abu.camera
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class CameraXActivity : AppCompatActivity(), LifecycleOwner, View.OnClickListener {
    private val TAG = "ABu-CameraX"

    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private val REQUEST_CODE_PERMISSIONS = 0x01

    private lateinit var textureView: TextureView

    private var imageCapture: ImageCapture? = null
    private var lensFacing: CameraX.LensFacing = CameraX.LensFacing.BACK


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        init()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            textureView.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun init() {
        findViewById<ImageView>(R.id.iv_switch).setOnClickListener(this)
        findViewById<ImageView>(R.id.iv_capture).setOnClickListener(this)

        textureView = findViewById(R.id.texture_view)
        textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }

        window?.statusBarColor= Color.BLACK
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                textureView.post { startCamera() }
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateTransform() {
        val matrix = Matrix()
        // Compute the center of the TextureView
        val centerX = textureView.width / 2f
        val centerY = textureView.height / 2f
        // Correct preview output to account for display rotation
        val rotationDegrees = when (textureView.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)
        // Finally, apply transformations to our TextureView
        textureView.setTransform(matrix)
    }

    private fun startCamera() {
        CameraX.unbindAll()
        val preview = getPreview()

        /** For Capture */
        imageCapture = getImageCapture()

        /** For Analyzer */
        val imageAnalysis = getImageAnalyzer()

        //CameraX.bindToLifecycle(this, preview, imageCapture)
        CameraX.bindToLifecycle(this, preview, imageCapture, imageAnalysis)
    }

    private fun getPreview(): Preview {
        /** Other configuration
        val metrics = DisplayMetrics().also { textureView.display.getRealMetrics(it) }
        val screenSize = Size(metrics.widthPixels, metrics.heightPixels)
        val screenAspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)
        val previewConfig = PreviewConfig.Builder().apply {
        }.build()
        */
        val previewConfig = PreviewConfig.Builder().apply {
            setLensFacing(lensFacing)
        }.build()
        val preview = Preview(previewConfig)
        preview.setOnPreviewOutputUpdateListener {
            // To update the SurfaceTexture, we have to remove it and re-add it
            val parent = textureView.parent as ViewGroup
            parent.removeView(textureView)
            parent.addView(textureView, 0)
            textureView.surfaceTexture = it.surfaceTexture
        }
        return preview
    }

    private fun getImageCapture(): ImageCapture {
        val imageCaptureConfig = ImageCaptureConfig.Builder().apply {
            setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            setLensFacing(lensFacing)
        }.build()
        return ImageCapture(imageCaptureConfig)
    }

    private fun getImageAnalyzer(): ImageAnalysis {
        // Setup image analysis pipeline that computes average pixel luminance
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            // In our analysis, we care more about the latest image than analyzing *every* image
            setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
            setLensFacing(lensFacing)
        }.build()
        return ImageAnalysis(analyzerConfig).apply { setAnalyzer(LuminosityAnalyzer()) }
    }

    private class LuminosityAnalyzer : ImageAnalysis.Analyzer {
        private var lastAnalyzedTimestamp = 0L
        /**
         * Helper extension function used to extract a byte array from an
         * image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy, rotationDegrees: Int) {
            val currentTimestamp = System.currentTimeMillis()
            // Calculate the average luma no more often than every second
            if (currentTimestamp - lastAnalyzedTimestamp >= TimeUnit.SECONDS.toMillis(1)) {
                // Since format in ImageAnalysis is YUV, image.planes[0]
                // contains the Y (luminance) plane
                val buffer = image.planes[0].buffer
                // Extract image data from callback object
                val data = buffer.toByteArray()
                // Convert the data into an array of pixel values
                val pixels = data.map { it.toInt() and 0xFF }
                // Compute average luminance for the image
                val luma = pixels.average()
                // Log the new luma value
                Log.d("CameraXApp", "Average luminosity: $luma")
                // Update timestamp of last analyzed frame
                lastAnalyzedTimestamp = currentTimestamp
            }
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.iv_capture -> imageCapture?.takePicture(
                File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg"),
                imageCaptureListener)
            R.id.iv_switch -> {
                lensFacing = if (lensFacing == CameraX.LensFacing.BACK) {
                    CameraX.LensFacing.FRONT
                } else {
                    CameraX.LensFacing.BACK
                }
                startCamera()
            }
        }
    }

    private val imageCaptureListener = object : ImageCapture.OnImageSavedListener {
        override fun onError(error: ImageCapture.ImageCaptureError, message: String, exc: Throwable?) {
            Log.e(TAG, "Photo capture failed: $message", exc)
            textureView.post {
                Toast.makeText(baseContext, "Photo capture failed: $message", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        override fun onImageSaved(file: File) {
            Log.d(TAG, "Photo capture succeeded: ${file.absolutePath}")
            textureView.post {
                Toast.makeText(this@CameraXActivity, "File: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }
}