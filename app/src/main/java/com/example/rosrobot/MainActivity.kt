package com.example.rosrobot

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.*
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.*
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.cancel
import com.google.ar.core.Config

class MainActivity : AppCompatActivity(), ImageAnalysis.Analyzer, android.hardware.SensorEventListener {

    // Add these class properties
    private var movementTimer: Timer? = null
    private var initialPose: Pose? = null
    private val COORDINATE_UPDATE_INTERVAL = 1000L
    companion object {
        private const val DEFAULT_MOVE_DURATION = 2000L // 2 seconds
    }// UI Components
    private lateinit var btnConnect: Button
    private lateinit var txtCommand: TextView
    private lateinit var txtResponse: TextView
    private lateinit var edtEspIp: EditText
    private lateinit var previewView: PreviewView

    // Voice components
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer

    // Network
    private val client = OkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // AR and Sensors
    private var arSession: Session? = null
    private lateinit var sensorManager: android.hardware.SensorManager
    private var latestAccelerometerData: FloatArray? = null
    private var latestGyroscopeData: FloatArray? = null

    // Detection
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    // Navigation
    private var currentTarget: TargetType? = null
    private val faceDatabase = mutableMapOf<String, FaceData>()
    private var obstacleDetected = false
    private var currentFace: Face? = null
    private var currentObjects = emptyList<DetectedObject>()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler()
    private val navigationHandler = Handler()
    private val NAVIGATION_INTERVAL_MS = 1000L

    // Target types
    sealed class TargetType {
        data class FaceTarget(val name: String) : TargetType()
        data class ObjectTarget(val objectName: String) : TargetType()
        data class CoordinateTarget(val pose: Pose) : TargetType()
        object FollowMe : TargetType()
    }

    data class FaceData(
        val embeddings: List<Float>,
        val lastKnownPosition: PointF? = null
    )

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUI()
        checkPermissions()
        initializeVoiceComponents()
        initializeSensors()
        initializeCamera()
        initializeARCore()
        startNavigationLoop()
    }

    private fun initializeUI() {
        btnConnect = findViewById(R.id.btnConnect)
        txtCommand = findViewById(R.id.txtCommand)
        txtResponse = findViewById(R.id.txtResponse)
        edtEspIp = findViewById(R.id.edtEspIp)
        previewView = findViewById(R.id.previewView)

        btnConnect.setOnClickListener { connectToESP() }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val ungrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungrantedPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungrantedPermissions.toTypedArray(), 100)
        }
    }

    private fun initializeVoiceComponents() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
            }
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        txtCommand.text = it
                        processVoiceCommand(it)
                    }
                    startListening()
                }

                override fun onError(error: Int) {
                    txtResponse.text = "Speech error: $error"
                    Handler().postDelayed({ startListening() }, 1000)
                }

                // Other empty overrides...
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        startListening()
    }

    private fun initializeSensors() {
        sensorManager = getSystemService(android.hardware.SensorManager::class.java)
        sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
    }

    @ExperimentalGetImage
    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                // Use getSurfaceProvider() method
                it.setSurfaceProvider(previewView.getSurfaceProvider())

            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, this) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                txtResponse.text = "Camera initialization failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun updateCoordinates() {
        val currentPose = arSession?.update()?.camera?.pose ?: return

        if (initialPose == null) {
            // Initialize coordinate system
            initialPose = currentPose
            announce("Coordinate system initialized at origin")
            return
        }

        // Calculate relative coordinates
        val x = currentPose.tz() - initialPose!!.tz()  // Forward/backward
        val y = currentPose.tx() - initialPose!!.tx()  // Left/right
        val z = currentPose.ty() - initialPose!!.ty()  // Up/down

        // Update UI with formatted coordinates
        runOnUiThread {
            txtResponse.text = String.format(Locale.US,
                "Position:\nX: %.2fm\nY: %.2fm\nZ: %.2fm",
                x, y, z
            )
        }
    }

    private fun startNavigationLoop() {
        navigationHandler.post(object : Runnable {
            override fun run() {
                navigate()
                updateCoordinates()
                navigationHandler.postDelayed(this, NAVIGATION_INTERVAL_MS)
            }
        })
    }

    private fun sendTimedMovementCommand(command: String, duration: Long? = null) {
        // Clear any current navigation target
        currentTarget = null

        // Stop any previous movement
        movementTimer?.cancel()
        sendCommandToESP("stop")

        // Start new movement
        sendCommandToESP(command)
        announce("Moving: ${command.replace("_", " ")}")

        // If duration is specified or not specified (default to 2 seconds)
        if (duration != Long.MAX_VALUE) {  // MAX_VALUE would mean "continuous"
            val actualDuration = duration ?: DEFAULT_MOVE_DURATION
            movementTimer = Timer().apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        sendCommandToESP("stop")
                        announce("Movement completed")
                    }
                }, actualDuration)
            }
        }
    }
    // Voice command processing
    private fun processVoiceCommand(command: String) {
        val cmd = command.lowercase(Locale.getDefault())
        when {
            cmd.contains("buddy") -> handleBuddyCommand(cmd.replace("buddy", "").trim())
            cmd.startsWith("go to") -> handleGoToCommand(cmd.replace("go to", "").trim())
            cmd == "follow me" -> {
                currentTarget = TargetType.FollowMe
                announce("Following you")
            }
            cmd == "move forward" -> sendTimedMovementCommand("move_forward", Long.MAX_VALUE)
            cmd == "move backward" -> sendTimedMovementCommand("move_backward", Long.MAX_VALUE)
            cmd == "turn left" -> sendTimedMovementCommand("turn_left", Long.MAX_VALUE)
            cmd == "turn right" -> sendTimedMovementCommand("turn_right", Long.MAX_VALUE)
            cmd == "stop" -> {
                movementTimer?.cancel()
                sendCommandToESP("stop")
                announce("Stopped")
            }

            // Timed movement commands (default to 2 seconds if no duration specified)
            cmd.startsWith("move forward") -> handleTimedCommand(cmd, "move_forward")
            cmd.startsWith("move backward") -> handleTimedCommand(cmd, "move_backward")
            cmd.startsWith("turn left") -> handleTimedCommand(cmd, "turn_left")
            cmd.startsWith("turn right") -> handleTimedCommand(cmd, "turn_right")

            else -> announce("Command not recognized")
        }
    }

    private fun handleTimedCommand(fullCommand: String, baseCommand: String) {
        val duration = try {
            fullCommand.replace(Regex("[^0-9]"), "").takeIf { it.isNotEmpty() }?.toLong()?.times(1000)
        } catch (e: NumberFormatException) {
            null
        }

        sendTimedMovementCommand(baseCommand, duration)
    }

    private fun extractMockEmbedding(face: Face): List<Float> {
        return listOf(
            face.boundingBox.centerX().toFloat(),
            face.boundingBox.centerY().toFloat()
        )
    }

    private fun handleBuddyCommand(command: String) {
        when {
            command.startsWith("he is") -> {
                val name = command.replace("he is", "").trim()
                currentFace?.let { face ->
                    val faceData = FaceData(
                        embeddings = extractMockEmbedding(face),
                        lastKnownPosition = calculateFaceCenter(face)
                    )
                    faceDatabase[name] = faceData
                    announce("Face saved for $name")
                } ?: announce("No face detected to save for $name")
            }
            else -> announce("Buddy command not recognized")
        }
    }

    private fun handleGoToCommand(target: String) {
        currentTarget = when {
            target in faceDatabase -> TargetType.FaceTarget(target)
            target.matches(Regex("[0-9.]+,[0-9.]+")) -> {
                val coords = target.split(",").map { it.toFloat() }
                TargetType.CoordinateTarget(
                    Pose(
                        floatArrayOf(coords[0], 0f, coords[1]),
                        floatArrayOf(0f, 0f, 0f, 1f)
                    )
                )
            }
            else -> TargetType.ObjectTarget(target)
        }
        announce("Target set to $target")
    }

    // Image analysis
    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image ?: run {
            image.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)

        when (currentTarget) {
            is TargetType.FaceTarget -> detectFaces(inputImage, image)
            else -> detectObjects(inputImage, image)
        }
    }

    private fun isMatching(e1: List<Float>, e2: List<Float>): Boolean {
        val dx = e1[0] - e2[0]
        val dy = e1[1] - e2[1]
        val distance = sqrt(dx * dx + dy * dy)
        return distance < 100 // adjust threshold as needed
    }

    private fun detectFaces(inputImage: InputImage, image: ImageProxy) {
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                currentFace = faces.firstOrNull()
                currentFace?.let { face ->
                    if (currentTarget is TargetType.FaceTarget) {
                        updateFacePosition(face)
                    } else {
                        // Check if this face matches any in database
                        val embedding = extractMockEmbedding(face)
                        faceDatabase.forEach { (name, data) ->
                            if (isMatching(embedding, data.embeddings)) {
                                currentTarget = TargetType.FaceTarget(name)
                                updateFacePosition(face)
                            }
                        }
                    }
                }
                image.close()
            }
            .addOnFailureListener {
                image.close()
            }
    }

    private fun detectObjects(inputImage: InputImage, image: ImageProxy) {
        objectDetector.process(inputImage)
            .addOnSuccessListener { objects ->
                currentObjects = objects
                obstacleDetected = objects.any { obj ->
                    obj.labels.any { label ->
                        label.text.equals("chair", true) ||
                                label.text.equals("table", true)
                    }
                }
                image.close()
            }
            .addOnFailureListener {
                image.close()
            }
    }

    private fun navigate() {
        when (val target = currentTarget) {
            is TargetType.CoordinateTarget -> handleCoordinateNavigation(target)
            is TargetType.FaceTarget -> handleFaceNavigation(target)
            is TargetType.ObjectTarget -> handleObjectNavigation(target)
            is TargetType.FollowMe -> handleFollowMe()
            null -> return
        }
    }

    private fun handleCoordinateNavigation(target: TargetType.CoordinateTarget) {
        val targetPose = target.pose
        val currentPose = arSession?.update()?.camera?.pose ?: run {
            navigateUsingSensors()
            return
        }

        if (calculateDistance(currentPose, targetPose) < 0.5f) {
            announce("Target reached")
            currentTarget = null
            return
        }

        sendCommandToESP(calculateMovementCommand(currentPose, targetPose))
    }

    private fun handleFaceNavigation(target: TargetType.FaceTarget) {
        currentFace?.let { face ->
            val faceCenter = calculateFaceCenter(face)
            sendCommandToESP(
                when {
                    faceCenter.x < 0.3 -> "turn_left"
                    faceCenter.x > 0.7 -> "turn_right"
                    else -> "move_forward"
                }
            )
            updateFacePosition(face)
        } ?: announce("Target face not detected")
    }

    private fun handleObjectNavigation(target: TargetType.ObjectTarget) {
        currentObjects.firstOrNull { obj ->
            obj.labels.any { label ->
                label.text.equals(target.objectName, ignoreCase = true)
            }
        }?.let { targetObj ->
            val objCenter = calculateObjectCenter(targetObj)
            sendCommandToESP(
                when {
                    objCenter.x < 0.3 -> "turn_left"
                    objCenter.x > 0.7 -> "turn_right"
                    else -> "move_forward"
                }
            )
        } ?: announce("Target object not found")
    }

    private fun handleFollowMe() {
        if (obstacleDetected) {
            sendCommandToESP("stop")
            announce("Obstacle detected")
        } else {
            currentFace?.let { face ->
                val faceCenter = calculateFaceCenter(face)
                sendCommandToESP(
                    when {
                        faceCenter.x < 0.3 -> "turn_left"
                        faceCenter.x > 0.7 -> "turn_right"
                        else -> "move_forward"
                    }
                )
            } ?: sendCommandToESP("stop")
        }
    }

    // Helper functions
    private fun calculateDistance(p1: Pose, p2: Pose): Float {
        return sqrt((p1.tx() - p2.tx()).pow(2) + (p1.tz() - p2.tz()).pow(2))
    }

    private fun calculateMovementCommand(current: Pose, target: Pose): String {
        val dx = target.tx() - current.tx()
        val dz = target.tz() - current.tz()
        val angleToTarget = atan2(dz, dx)
        val yaw = getYawFromPose(current)
        val angleDiff = angleToTarget - yaw

        return when {
            angleDiff > 0.3 -> "turn_right"
            angleDiff < -0.3 -> "turn_left"
            else -> "move_forward"
        }
    }

    private fun getYawFromPose(pose: Pose): Double {
        val q = pose.rotationQuaternion
        return atan2(2.0 * (q[0] * q[3] + q[1] * q[2]), 1.0 - 2.0 * (q[2] * q[2] + q[3] * q[3]))
    }

    private fun calculateFaceCenter(face: Face): PointF {
        val bounds = face.boundingBox
        return PointF(
            (bounds.left + bounds.right) / 2f / previewView.width,
            (bounds.top + bounds.bottom) / 2f / previewView.height
        )
    }

    private fun calculateObjectCenter(obj: DetectedObject): PointF {
        val bounds = obj.boundingBox
        return PointF(
            (bounds.left + bounds.right) / 2f / previewView.width,
            (bounds.top + bounds.bottom) / 2f / previewView.height
        )
    }

    private fun updateFacePosition(face: Face) {
        val name = (currentTarget as? TargetType.FaceTarget)?.name ?: return
        faceDatabase[name] = faceDatabase[name]?.copy(lastKnownPosition = calculateFaceCenter(face))
            ?: FaceData(emptyList(), calculateFaceCenter(face))
    }

    private fun navigateUsingSensors() {
        val az = latestAccelerometerData?.get(2) ?: 0f
        val gz = latestGyroscopeData?.get(2) ?: 0f
        sendCommandToESP(
            when {
                az < 5 -> "move_forward"
                gz > 1.0f -> "turn_right"
                gz < -1.0f -> "turn_left"
                else -> "stop"
            }
        )
    }

    // Network communication
    private fun connectToESP() {
        val ip = edtEspIp.text.toString().trim().ifEmpty { "192.168.0.160" }
        val request = Request.Builder()
            .url("http://$ip/command?text=hello")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { txtResponse.text = "Connection error: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    txtResponse.text = if (response.isSuccessful) "ESP Connected" else "Connection failed"
                }
            }
        })
    }

    private fun sendCommandToESP(command: String) {
        val ip = edtEspIp.text.toString().trim().ifEmpty { "192.168.0.160" }
        val request = Request.Builder()
            .url("http://$ip/command?text=${Uri.encode(command)}")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { txtResponse.text = "Command failed: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let {
                    runOnUiThread { txtResponse.text = it }
                }
            }
        })
    }

    // Utility functions
    private fun announce(message: String) {
        runOnUiThread {
            textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "")
            txtResponse.text = message
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            txtResponse.text = "Speech recognition error: ${e.message}"
        }
    }

    // ARCore Session Management
    private fun initializeARCore() {
        initialPose = null
        when (ArCoreApk.getInstance().requestInstall(this@MainActivity, true)) {
            ArCoreApk.InstallStatus.INSTALLED -> {
                try {
                    arSession = Session(this@MainActivity).apply {
                        val config = Config(this).apply {
                            // CORRECTED: Use lightEstimationMode instead of setLightingMode
                            lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        }
                        configure(config)
                    }
                    runOnUiThread { txtResponse.text = "ARCore initialized" }
                } catch (e: Exception) {
                    runOnUiThread { txtResponse.text = "ARCore failed: ${e.message}" }
                }
            }
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                runOnUiThread { txtResponse.text = "Please install ARCore from Play Store" }
            }
            else -> {
                runOnUiThread { txtResponse.text = "ARCore not supported" }
            }
        }
    }

    // Sensor events
    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            android.hardware.Sensor.TYPE_ACCELEROMETER -> latestAccelerometerData = event.values.clone()
            android.hardware.Sensor.TYPE_GYROSCOPE -> latestGyroscopeData = event.values.clone()
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech.shutdown()
        speechRecognizer.destroy()
        sensorManager.unregisterListener(this)
        arSession?.close()
        cameraExecutor.shutdown()
        handler.removeCallbacksAndMessages(null)
        navigationHandler.removeCallbacksAndMessages(null)
        coroutineScope.cancel()
    }
}