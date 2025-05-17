package com.programminghut.realtime_object

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.programminghut.realtime_object.ml.SsdMobilenetV11Metadata1
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    lateinit var labels: List<String>
    var colors = listOf<Int>(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED
    )
    val paint = Paint()
    lateinit var imageProcessor: ImageProcessor
    lateinit var bitmap: Bitmap
    lateinit var imageView: ImageView
    lateinit var cameraDevice: CameraDevice
    lateinit var handler: Handler
    lateinit var cameraManager: CameraManager
    lateinit var textureView: TextureView
    lateinit var model: SsdMobilenetV11Metadata1
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var detectedObjectsText: TextView
    private lateinit var backButton: Button
    private var lastSpokenObjects = ""
    private var lastSpokenTime = 0L
    private var lastProcessedTime = 0L
    private val speechDelay = 3000L // Delay between speech outputs (3 seconds)
    private val processingDelay = 500L // Reduced delay for more responsive object detection (0.5 seconds)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        get_permission()

        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)

        // Initialize UI components
        imageView = findViewById(R.id.imageView)
        detectedObjectsText = findViewById(R.id.detectedObjectsText)
        backButton = findViewById(R.id.backButton)

        // Set up back button
        backButton.setOnClickListener {
            speakOut("Going back to home screen")
            finish()
        }

        // Initialize object detection model in a background thread
        Thread {
            try {
                labels = FileUtil.loadLabels(this, "labels.txt")
                // Use a faster resize method and smaller model input size
                imageProcessor = ImageProcessor.Builder()
                    .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
                    .build()
                model = SsdMobilenetV11Metadata1.newInstance(this)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
        
        // Create handler thread for camera processing
        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        textureView = findViewById(R.id.textureView)
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
                // Process frames with a delay to reduce lag and improve stability
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProcessedTime > processingDelay) {
                    try {
                        // Get the bitmap and make a copy to avoid memory issues
                        val originalBitmap = textureView.bitmap
                        if (originalBitmap != null) {
                            bitmap = originalBitmap.copy(originalBitmap.config, true)
                            
                            // Process the image in a try-catch block to handle potential errors
                            try {
                                // Use a lightweight bitmap configuration to reduce memory usage
                                var image = TensorImage.fromBitmap(bitmap)
                                image = imageProcessor.process(image)

                                // Process the image on a background thread to avoid UI freezing
                                val outputs = model.process(image)
                                val locations = outputs.locationsAsTensorBuffer.floatArray
                                val classes = outputs.classesAsTensorBuffer.floatArray
                                val scores = outputs.scoresAsTensorBuffer.floatArray

                                // Use RGB_565 instead of ARGB_8888 to reduce memory usage by half
                                var mutable = bitmap.copy(Bitmap.Config.RGB_565, true)
                                val canvas = Canvas(mutable)

                                val h = mutable.height
                                val w = mutable.width
                                paint.textSize = h / 15f
                                paint.strokeWidth = h / 85f
                                var x = 0
                                
                                // Build a list of detected objects
                                val detectedObjects = mutableListOf<String>()
                                
                                scores.forEachIndexed { index, fl ->
                                    if (index < scores.size && index < classes.size) {
                                        x = index
                                        x *= 4
                                        if (fl > 0.5 && x + 3 < locations.size) {
                                            try {
                                                val classIndex = classes[index].toInt()
                                                if (classIndex >= 0 && classIndex < labels.size) {
                                                    val label = labels[classIndex]
                                                    detectedObjects.add(label)
                                                    
                                                    paint.color = colors[index % colors.size]
                                                    paint.style = Paint.Style.STROKE
                                                    canvas.drawRect(
                                                        RectF(
                                                            locations[x + 1] * w,
                                                            locations[x] * h,
                                                            locations[x + 3] * w,
                                                            locations[x + 2] * h
                                                        ), paint
                                                    )
                                                    paint.style = Paint.Style.FILL
                                                    canvas.drawText(
                                                        "$label ${fl.toString().take(4)}",
                                                        locations[x + 1] * w,
                                                        locations[x] * h,
                                                        paint
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                // Continue with the next object if there's an error
                                            }
                                        }
                                    }
                                }

                                // Update the image view with the detection results on the UI thread
                                runOnUiThread {
                                    imageView.setImageBitmap(mutable)
                                    
                                    // Update the text view with detected objects
                                    if (detectedObjects.isNotEmpty()) {
                                        val objectsText = "Detected: ${detectedObjects.distinct().joinToString(", ")}"
                                        detectedObjectsText.text = objectsText
                                        
                                        // Speak out the detected objects if they've changed and enough time has passed
                                        val speakTime = System.currentTimeMillis()
                                        val objectsString = detectedObjects.distinct().joinToString(", ")
                                        if (objectsString != lastSpokenObjects && speakTime - lastSpokenTime > speechDelay) {
                                            if (objectsString.isNotEmpty()) {
                                                speakOut("I can see $objectsString")
                                                lastSpokenObjects = objectsString
                                                lastSpokenTime = speakTime
                                            }
                                        }
                                    } else {
                                        detectedObjectsText.text = "No objects detected"
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            
                            lastProcessedTime = currentTime
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Initial instruction
        speakOut("Object recognition activated. Point your camera at objects to identify them.")
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
            if (::model.isInitialized) {
                model.close()
            }
            // Recycle bitmap if it exists
            if (::bitmap.isInitialized && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            super.onDestroy()
        }
    }

    @SuppressLint("MissingPermission")
    fun open_camera() {
        try {
            // Get the optimal camera preview size
            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            // Open the camera with optimized settings
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        cameraDevice = camera

                        // Configure the texture view for optimal preview
                        val surfaceTexture = textureView.surfaceTexture
                        // Set buffer size to reduce memory usage
                        val previewSize = streamConfigurationMap?.getOutputSizes(SurfaceTexture::class.java)?.get(0)
                        if (previewSize != null) {
                            surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
                        }
                        
                        val surface = Surface(surfaceTexture)

                        // Create an optimized capture request
                        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        captureRequest.addTarget(surface)
                        
                        // Set capture request parameters for better performance
                        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                        // Create the capture session with optimized settings
                        cameraDevice.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.setRepeatingRequest(captureRequest.build(), null, null)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    runOnUiThread {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Camera configuration failed",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Camera preview error: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                Toast.makeText(
                    this@MainActivity,
                    "Camera error: $error",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, handler)
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(
            this,
            "Error opening camera: ${e.message}",
            Toast.LENGTH_SHORT
        ).show()
    }
    }

    fun get_permission() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
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
}
