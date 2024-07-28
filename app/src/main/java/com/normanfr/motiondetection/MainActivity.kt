package com.normanfr.motiondetection

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private var serverIp: String = "192.168.0.155" //preset
    private var sensitivity: Int = 10000

    private val TAG = "MotionDetection"
    private val MIN_DIFF_THRESHOLD = 10000

    private var lastFrameBuffer: ByteArray? = null
    private var lastRequestTime: Long = 0
    private val requestInterval: Long = 5000

    private val permissions = arrayOf(Manifest.permission.CAMERA)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "All permissions are required to use the camera", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        sharedPreferences = getSharedPreferences("MotionDetectionPrefs", MODE_PRIVATE)
        serverIp = sharedPreferences.getString("server_ip", "192.168.0.155")!!
        sensitivity = sharedPreferences.getInt("sensitivity", 500)

        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(permissions)
        } else {
            startCamera()
        }
    }

    private fun allPermissionsGranted() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)

                val imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(480, 640))
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .build()

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), object : ImageAnalysis.Analyzer {
                    override fun analyze(image: ImageProxy) {
                        detectMotion(image, imageCapture)
                        image.close()
                    }
                })

                val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

                cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis)

            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detectMotion(image: ImageProxy, imageCapture: ImageCapture) {
        if (image.format != ImageFormat.YUV_420_888) {
            //decline process if not YUV
            return
        }

        val buffer = image.planes[0].buffer
        val currentFrameBuffer = ByteArray(buffer.remaining())
        buffer.get(currentFrameBuffer)

        if (lastFrameBuffer != null) {
            if (calculateDifference(lastFrameBuffer!!, currentFrameBuffer) > MIN_DIFF_THRESHOLD) {
                Log.d(TAG, "Motion detected")

                if (canSendRequest()) {
                    captureImage(imageCapture)
                }
            }
        }
        lastFrameBuffer = currentFrameBuffer
    }

    private fun calculateDifference(oldFrame: ByteArray, newFrame: ByteArray): Int {
        var diff = 0
        val step = sensitivity
        for (i in oldFrame.indices step step) {
            diff += abs(oldFrame[i] - newFrame[i])
        }
        return diff
    }

    private fun canSendRequest(): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime - lastRequestTime >= requestInterval
    }

    private fun captureImage(imageCapture: ImageCapture) {
        val photoFile = createFile()

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Image capture succeeded: ${outputFileResults.savedUri}")

                    photoFile.inputStream().use { inputStream ->
                        val byteArray = inputStream.readBytes()
                        val rotatedImage = rotateImage(byteArray)
                        sendImageToServer(rotatedImage)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    private fun createFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(null)
        return File.createTempFile("JPEG_$timeStamp", ".jpg", storageDir)
    }

    private fun rotateImage(imageData: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
        val matrix = Matrix().apply {
            postRotate(90f)
        }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val outputStream = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        return outputStream.toByteArray()
    }

    private fun sendImageToServer(imageData: ByteArray) {
        if (!canSendRequest()) {
            Log.d(TAG, "Request blocked by timeout")
            return
        }

        lastRequestTime = System.currentTimeMillis()

        val urlString = "http://$serverIp:8080/signal" //make sure to not include :8080 in settings
        Log.d(TAG, "Constructed URL: $urlString")

        val client = OkHttpClient()
        val mediaType = "image/jpeg".toMediaType()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "screenshot.jpeg", imageData.toRequestBody(mediaType))
            .build()

        val request = Request.Builder()
            .url(urlString)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Error sending image to server", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "Image sent successfully. Server response: ${response.body?.string()}")
                } else {
                    Log.d(TAG, "Failed to send image. Response Code: ${response.code}")
                    Log.e(TAG, "Server error response: ${response.body?.string()}")
                }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
