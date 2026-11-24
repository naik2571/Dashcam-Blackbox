package com.dashcam.blackbox

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.dashcam.blackbox.databinding.ActivityMainBinding
import com.dashcam.blackbox.recorder.RollingBufferManager
import com.dashcam.blackbox.sensor.ImpactDetector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ImpactDetector.ImpactListener, SensorEventListener, LocationListener {

    companion object {
        const val TAG = "DashcamMainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var bufferManager: RollingBufferManager
    private lateinit var impactDetector: ImpactDetector

    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var currentChunkFile: File? = null
    private var isRecording = false

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sensorManager: SensorManager
    private var rotationVector: Sensor? = null
    private var locationManager: LocationManager? = null

    private var recordDurationSeconds = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val clockRunnable = object : Runnable {
        override fun run() {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvTimestamp.text = sdf.format(Date())

            if (isRecording) {
                recordDurationSeconds++
                val m = recordDurationSeconds / 60
                val s = recordDurationSeconds % 60
                binding.tvTimer.text = String.format(" %02d:%02d", m, s)
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val camGranted = results[Manifest.permission.CAMERA] ?: false
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
        if (camGranted && audioGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera and Audio permissions required for Dashcam", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bufferManager = RollingBufferManager(this)
        impactDetector = ImpactDetector(this).apply {
            listener = this@MainActivity
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setupListeners()
        updateLockedClipsCount()

        checkPermissionsAndStart()
    }

    private fun setupListeners() {
        binding.btnLockIncident.setOnClickListener {
            val locked = bufferManager.lockIncidentFile(currentChunkFile)
            if (locked != null) {
                triggerHapticShock()
                updateLockedClipsCount()
                Toast.makeText(this, "🚨 Incident Clip Manually Locked!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Recording buffer initialized", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMasterRecord.setOnClickListener {
            if (isRecording) {
                pauseRecording()
            } else {
                startRollingRecord()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture
                )
                startRollingRecord()
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding error: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startRollingRecord() {
        val capture = videoCapture ?: return

        currentChunkFile = bufferManager.getNewChunkFile()
        val fileOptions = FileOutputOptions.Builder(currentChunkFile!!).build()

        currentRecording = capture.output
            .prepareRecording(this, fileOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        updateRecordUI(true)
                        Log.i(TAG, "Dashcam chunk started: ${currentChunkFile?.name}")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(TAG, "Recording chunk error: ${event.error}")
                        }
                    }
                }
            }
    }

    private fun pauseRecording() {
        currentRecording?.stop()
        currentRecording = null
        isRecording = false
        updateRecordUI(false)
    }

    private fun updateRecordUI(recording: Boolean) {
        if (recording) {
            binding.tvRecIndicator.text = "🔴 REC"
            binding.tvRecIndicator.setTextColor(Color.parseColor("#EF4444"))
            binding.btnMasterRecord.text = "PAUSE DASHCAM"
            binding.btnMasterRecord.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#06B6D4"))
        } else {
            binding.tvRecIndicator.text = "⏸️ PAUSED"
            binding.tvRecIndicator.setTextColor(Color.parseColor("#94A3B8"))
            binding.btnMasterRecord.text = "RESUME DASHCAM"
            binding.btnMasterRecord.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
        }
    }

    override fun onImpactDetected(peakG: Float, impactType: String) {
        runOnUiThread {
            Log.w(TAG, "IMPACT TRIGGERED! Peak: $peakG G, Type: $impactType")
            val locked = bufferManager.lockIncidentFile(currentChunkFile)
            triggerHapticShock()
            updateLockedClipsCount()

            binding.layoutGCard.setBackgroundColor(Color.parseColor("#90EF4444"))
            binding.tvImpactStatus.text = "⚠️ $impactType (${String.format("%.2f", peakG)}G) - CLIP LOCKED!"
            binding.tvImpactStatus.setTextColor(Color.WHITE)

            mainHandler.postDelayed({
                binding.layoutGCard.setBackgroundColor(Color.parseColor("#70000000"))
                binding.tvImpactStatus.text = "SHOCK SENSOR: ARMED (2.2G)"
                binding.tvImpactStatus.setTextColor(Color.parseColor("#10B981"))
            }, 4000L)

            Toast.makeText(this, "⚠️ COLLISION IMPACT DETECTED! Video Clip Locked.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onGForceUpdate(currentG: Float, lateralG: Float, longitudinalG: Float) {
        runOnUiThread {
            binding.tvGForceTotal.text = String.format("%.2f G", currentG)
            binding.tvGForceAxes.text = String.format("Lat: %+.2fG | Long: %+.2fG", lateralG, longitudinalG)
        }
    }

    private fun triggerHapticShock() {
        val pattern = longArrayOf(0, 100, 50, 200, 50, 400)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }

    private fun updateLockedClipsCount() {
        val count = bufferManager.getIncidentCount()
        binding.tvLockedCount.text = "Locked Incidents: $count clips"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (azimuth < 0) azimuth += 360f

        val direction = when (azimuth) {
            in 22.5..67.5 -> "NE"
            in 67.5..112.5 -> "E"
            in 112.5..157.5 -> "SE"
            in 157.5..202.5 -> "S"
            in 202.5..247.5 -> "SW"
            in 247.5..292.5 -> "W"
            in 292.5..337.5 -> "NW"
            else -> "N"
        }
        binding.tvHeading.text = "$direction ${azimuth.toInt()}°"
    }

    override fun onLocationChanged(location: Location) {
        val speedKmh = (location.speed * 3.6f).toInt().coerceAtLeast(0)
        binding.tvSpeedValue.text = String.format("%02d", speedKmh)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        mainHandler.post(clockRunnable)
        impactDetector.startListening()
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(clockRunnable)
        impactDetector.stopListening()
        sensorManager.unregisterListener(this)
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        currentRecording?.stop()
        cameraExecutor.shutdown()
    }
}
