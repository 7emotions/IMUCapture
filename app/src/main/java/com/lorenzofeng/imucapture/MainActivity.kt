package com.lorenzofeng.imucapture

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.annotation.SuppressLint
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "IMUCapture"
        private const val CAMERA_PERMISSION_CODE = 0
        private const val UI_UPDATE_INTERVAL_MS = 100L
        private const val MAX_LOG_LENGTH = 50_000
    }

    // ARCore
    private var session: Session? = null
    private var installRequested = false
    private var cameraTextureId = -1
    private var viewportWidth = 0
    private var viewportHeight = 0

    // Camera background rendering
    private var quadProgram = 0
    private var quadPositionAttrib = 0
    private var quadTexCoordAttrib = 0
    private lateinit var quadVertices: FloatBuffer
    private var transformedTexCoords: FloatBuffer? = null

    // UI
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var btnRecord: Button
    private lateinit var btnClear: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView

    // Tracking state
    @Volatile
    private var isRecording = false
    @Volatile
    private var originPose: Pose? = null
    private var lastUiUpdateTime: Long = 0

    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        btnRecord = findViewById(R.id.btnRecord)
        btnClear = findViewById(R.id.btnClear)
        tvLog = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)

        // Setup GLSurfaceView for ARCore
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

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
        originPose = null
        isRecording = true
        btnRecord.text = getString(R.string.recording)
        appendLog("=== Recording started ===")
    }

    private fun stopRecording() {
        isRecording = false
        originPose = null
        btnRecord.text = getString(R.string.hold_to_record)
        appendLog("=== Recording stopped ===\n")
    }

    // --- GLSurfaceView.Renderer ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Create camera texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Setup camera background quad shader
        setupCameraQuad()

        session?.setCameraTextureName(cameraTextureId)
    }

    @Suppress("DEPRECATION")
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(windowManager.defaultDisplay.rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = this.session ?: return

        val frame: Frame
        try {
            frame = session.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            return
        }

        // Draw camera background
        drawCameraBackground(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return
        if (!isRecording) return

        val currentPose = camera.pose

        if (originPose == null) {
            originPose = currentPose
        }

        val relativePose = originPose!!.inverse().compose(currentPose)
        val tx = relativePose.tx()
        val ty = relativePose.ty()
        val tz = relativePose.tz()

        val msg = String.format("pos=(%.4f, %.4f, %.4f) m", tx, ty, tz)

        Log.d(TAG, msg)
        logBuilder.appendLine(msg)
        throttledUpdateUi()
    }

    // --- Camera background rendering ---

    private fun setupCameraQuad() {
        val coords = floatArrayOf(
            -1.0f, -1.0f,
            +1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, +1.0f
        )
        val bb = ByteBuffer.allocateDirect(coords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        quadVertices = bb.asFloatBuffer()
        quadVertices.put(coords)
        quadVertices.position(0)

        val vertexShader = loadShader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec4 a_Position;\n" +
                "attribute vec2 a_TexCoord;\n" +
                "varying vec2 v_TexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = a_Position;\n" +
                "  v_TexCoord = a_TexCoord;\n" +
                "}\n"
        )
        val fragmentShader = loadShader(
            GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 v_TexCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, v_TexCoord);\n" +
                "}\n"
        )

        quadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(quadProgram, vertexShader)
        GLES20.glAttachShader(quadProgram, fragmentShader)
        GLES20.glLinkProgram(quadProgram)

        quadPositionAttrib = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordAttrib = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
    }

    private fun drawCameraBackground(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quadVertices.rewind()
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadVertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                getTexCoordBuffer()
            )
        }

        if (cameraTextureId == -1) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(quadProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        quadVertices.rewind()
        GLES20.glEnableVertexAttribArray(quadPositionAttrib)
        GLES20.glVertexAttribPointer(quadPositionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadVertices)

        val texCoords = transformedTexCoords
        if (texCoords != null) {
            texCoords.rewind()
            GLES20.glEnableVertexAttribArray(quadTexCoordAttrib)
            GLES20.glVertexAttribPointer(quadTexCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionAttrib)
        GLES20.glDisableVertexAttribArray(quadTexCoordAttrib)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun getTexCoordBuffer(): FloatBuffer {
        if (transformedTexCoords == null) {
            val bb = ByteBuffer.allocateDirect(8 * 4) // 4 vertices * 2 components
            bb.order(ByteOrder.nativeOrder())
            transformedTexCoords = bb.asFloatBuffer()
        }
        transformedTexCoords!!.rewind()
        return transformedTexCoords!!
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    // --- ARCore lifecycle ---

    override fun onResume() {
        super.onResume()

        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }

        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }
                session = Session(this)
                if (cameraTextureId != -1) {
                    session?.setCameraTextureName(cameraTextureId)
                }
            }
        } catch (e: UnavailableArcoreNotInstalledException) {
            appendLog("ARCore not installed")
            return
        } catch (e: UnavailableDeviceNotCompatibleException) {
            appendLog("Device not compatible with ARCore")
            return
        } catch (e: UnavailableApkTooOldException) {
            appendLog("ARCore APK too old, please update")
            return
        } catch (e: UnavailableSdkTooOldException) {
            appendLog("ARCore SDK too old")
            return
        } catch (e: Exception) {
            appendLog("Failed to create ARCore session: ${e.message}")
            return
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            appendLog("Camera not available: ${e.message}")
            session = null
            return
        }

        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) {
            stopRecording()
        }
        glSurfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    // --- Camera permission ---

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, onResume will handle session creation
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_needed),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    // --- UI helpers ---

    private fun appendLog(message: String) {
        logBuilder.appendLine(message)
        runOnUiThread { updateUi() }
    }

    private fun throttledUpdateUi() {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdateTime < UI_UPDATE_INTERVAL_MS) return
        lastUiUpdateTime = now
        runOnUiThread { updateUi() }
    }

    private fun updateUi() {
        if (logBuilder.length > MAX_LOG_LENGTH) {
            logBuilder.delete(0, logBuilder.length - MAX_LOG_LENGTH)
        }
        tvLog.text = logBuilder.toString()
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
