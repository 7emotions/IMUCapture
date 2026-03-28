package com.lorenzofeng.imucapture

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.annotation.SuppressLint
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "IMUCapture"
        private const val UI_UPDATE_INTERVAL_MS = 100L
        private const val MAX_LOG_LENGTH = 50_000
    }

    private lateinit var sensorManager: SensorManager
    private var linearAccSensor: Sensor? = null

    private lateinit var btnRecord: Button
    private lateinit var btnClear: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView

    private var isRecording = false

    // Velocity obtained by integrating acceleration (m/s)
    private var vx = 0.0
    private var vy = 0.0
    private var vz = 0.0

    // Position obtained by integrating velocity (m), origin at press moment
    private var px = 0.0
    private var py = 0.0
    private var pz = 0.0

    private var lastTimestamp: Long = 0
    private var lastUiUpdateTime: Long = 0

    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        btnClear = findViewById(R.id.btnClear)
        tvLog = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        linearAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        setupRecordButton()

        btnClear.setOnClickListener {
            logBuilder.clear()
            tvLog.text = getString(R.string.log_hint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecordButton() {
        btnRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRecording()
                    true
                }
                else -> false
            }
        }
    }

    private fun startRecording() {
        // Reset state: origin at press moment
        vx = 0.0; vy = 0.0; vz = 0.0
        px = 0.0; py = 0.0; pz = 0.0
        lastTimestamp = 0

        isRecording = true
        btnRecord.text = getString(R.string.recording)

        appendLog("=== Recording started ===")

        linearAccSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: appendLog("LINEAR_ACCELERATION sensor not available!")
    }

    private fun stopRecording() {
        isRecording = false
        sensorManager.unregisterListener(this)
        btnRecord.text = getString(R.string.hold_to_record)

        appendLog("=== Recording stopped ===\n")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording) return
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val timestamp = event.timestamp // nanoseconds

        if (lastTimestamp == 0L) {
            lastTimestamp = timestamp
            return
        }

        val dt = (timestamp - lastTimestamp) / 1_000_000_000.0 // seconds
        lastTimestamp = timestamp

        val ax = event.values[0].toDouble()
        val ay = event.values[1].toDouble()
        val az = event.values[2].toDouble()

        // Integrate acceleration to get velocity
        vx += ax * dt
        vy += ay * dt
        vz += az * dt

        // Integrate velocity to get position
        px += vx * dt
        py += vy * dt
        pz += vz * dt

        val msg = String.format(
            "pos=(%.4f, %.4f, %.4f) m  vel=(%.4f, %.4f, %.4f) m/s",
            px, py, pz, vx, vy, vz
        )

        Log.d(TAG, msg)
        logBuilder.appendLine(msg)
        throttledUpdateUi()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun appendLog(message: String) {
        logBuilder.appendLine(message)
        updateUi()
    }

    private fun throttledUpdateUi() {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdateTime < UI_UPDATE_INTERVAL_MS) return
        lastUiUpdateTime = now
        updateUi()
    }

    private fun updateUi() {
        if (logBuilder.length > MAX_LOG_LENGTH) {
            logBuilder.delete(0, logBuilder.length - MAX_LOG_LENGTH)
        }
        tvLog.text = logBuilder.toString()
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) {
            stopRecording()
        }
    }
}
