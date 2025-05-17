package com.programminghut.realtime_object

import android.Manifest
import android.R.string.cancel
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.NonCancellable.cancel
import java.util.*

// Extension function for safely canceling a Task
fun <T> Task<T>.safeCancel() {
    try {
        if (!isComplete) {
            cancel()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

class TextRecognitionActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var textToSpeech: TextToSpeech
    private lateinit var cameraDevice: CameraDevice
    private lateinit var handler: Handler
    private lateinit var cameraManager: CameraManager
    private lateinit var textureView: TextureView
    private lateinit var imageView: ImageView
    private lateinit var recognizedTextView: TextView
    private lateinit var backButton: Button
    private lateinit var textRecognizer: TextRecognizer
    private var lastProcessedText = ""
    private var lastSpokenTime = 0L
    private var lastProcessedTime = 0L
    private val speechDelay = 3000L // Delay between speech outputs (3 seconds)
    private val processingDelay = 700L // Optimized delay for text recognition (0.7 seconds)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_recognition)

        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)

        // Initialize UI components
        textureView = findViewById(R.id.textureView)
        imageView = findViewById(R.id.imageView)
        recognizedTextView = findViewById(R.id.recognizedTextView)
        backButton = findViewById(R.id.backButton)
        
        // Initialize ML Kit text recognizer in a background thread
        Thread {
            try {
                textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()

        // Set up camera
        get_permission()
        val handlerThread = HandlerThread("textRecognitionThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        // Set up TextureView listener
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                open_camera()
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
                // Not needed for this implementation
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                // Process the camera frame for text recognition with delay
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProcessedTime > processingDelay) {
                    val bitmap = textureView.bitmap
                    if (bitmap != null) {
                        // Create a copy of the bitmap to avoid memory issues
                        val bitmapCopy = bitmap.copy(bitmap.config, true)
                        recognizeText(bitmapCopy)
                        lastProcessedTime = currentTime
                    }
                }
            }
        }

        // Set up camera manager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Set up back button
        backButton.setOnClickListener {
            speakOut("Going back to home screen")
            finish()
        }

        // Initial instruction
        speakOut("Text recognition activated. Point your camera at text to read it aloud.")
    }

    @SuppressLint("SetTextI18n")
    private fun recognizeText(bitmap: Bitmap) {
        try {
            // Create a smaller image to process for better performance
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
            val image = InputImage.fromBitmap(scaledBitmap, 0)
            
            // Set a timeout for text recognition to prevent hanging
            val recognitionTask = textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    try {
                        val detectedText = visionText.text.trim()
                        
                        // Update UI with detected text
                        if (detectedText.isNotEmpty()) {
                            runOnUiThread {
                                recognizedTextView.text = detectedText
                            }
                            
                            // Speak the text if it's different from the last one and enough time has passed
                            val currentTime = System.currentTimeMillis()
                            if (detectedText != lastProcessedText && currentTime - lastSpokenTime > speechDelay) {
                                speakOut(detectedText)
                                lastProcessedText = detectedText
                                lastSpokenTime = currentTime
                            }
                        } else {
                            runOnUiThread {
                                recognizedTextView.text = "No text detected. Please try again."
                            }
                        }
                    } catch (e: Exception) {
                        // Handle any exceptions during text processing
                        e.printStackTrace()
                        runOnUiThread {
                            recognizedTextView.text = "Processing error. Please try again."
                        }
                    }
                    
                    // Recycle the scaled bitmap to free memory
                    if (scaledBitmap != bitmap) {
                        scaledBitmap.recycle()
                    }
                }
                .addOnFailureListener { e ->
                    runOnUiThread {
                        recognizedTextView.text = "Adjusting camera..."
                    }
                    e.printStackTrace()
                    
                    // Recycle the scaled bitmap to free memory
                    if (scaledBitmap != bitmap) {
                        scaledBitmap.recycle()
                    }
                }
            
            // Add a timeout to prevent hanging
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (!recognitionTask.isComplete) {
                        recognitionTask.safeCancel()
                        runOnUiThread {
                            recognizedTextView.text = "Recognition timed out. Please try again."
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        recognizedTextView.text = "Processing error. Please try again."
                    }
                }
            }, 5000) // 5 second timeout
            
        } catch (e: Exception) {
            // Handle any exceptions during image creation
            e.printStackTrace()
            runOnUiThread {
                recognizedTextView.text = "Camera error. Please restart the app."
            }
        } finally {
            // Make sure to recycle the bitmap to free memory if it's a copy
            if (bitmap != textureView.bitmap) {
                bitmap.recycle()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open_camera() {
        cameraManager.openCamera(cameraManager.cameraIdList[0], object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera

                val surfaceTexture = textureView.surfaceTexture
                val surface = Surface(surfaceTexture)

                val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)

                cameraDevice.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.setRepeatingRequest(captureRequest.build(), null, null)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Toast.makeText(
                                this@TextRecognitionActivity,
                                "Camera configuration failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    handler
                )
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                Toast.makeText(
                    this@TextRecognitionActivity,
                    "Camera error: $error",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, handler)
    }

    private fun get_permission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            get_permission()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language for TTS
            val result = textToSpeech.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Language not supported for Text to Speech", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Text to Speech initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speakOut(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    override fun onDestroy() {
        try {
            // Release resources
            if (::textToSpeech.isInitialized) {
                textToSpeech.stop()
                textToSpeech.shutdown()
            }
            if (::cameraDevice.isInitialized) {
                cameraDevice.close()
            }
            if (::textRecognizer.isInitialized) {
                textRecognizer.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            super.onDestroy()
        }
    }
}
