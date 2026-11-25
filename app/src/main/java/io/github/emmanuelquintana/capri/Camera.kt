package io.github.emmanuelquintana.capri

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * (1.13.0) Her camera, only when you ask ("mira esto", "qué ves", "tómame una foto"): ONE photo
 * with the back camera, then the camera closes. Android's own permission is asked the first
 * time, in the moment (CAMERA in the manifest is a runtime permission: nothing here works
 * without your yes, and there is no background capture of any kind — no preview kept, no
 * video, no second photo). The picture goes to her page (HostBridge "camera:shot"), which
 * puts it in the app's chat and, when her brain can see, asks it about the photo in her style.
 *
 * The asking works like her eyes (Eyes.kt): a latch, a see-through activity, an answer back.
 */
object CameraShot {
    const val OK = 0
    const val DENIED = 1
    const val FAILED = 2

    @Volatile private var pending: ((Int, ByteArray?) -> Unit)? = null

    /** One photo, as JPEG bytes. Blocking: never on the main thread. (DENIED, null) without your yes. */
    fun take(context: Context): Pair<Int, ByteArray?> {
        val latch = CountDownLatch(1)
        var out: Pair<Int, ByteArray?> = FAILED to null
        pending = { code, bytes -> out = code to bytes; latch.countDown() }
        context.startActivity(android.content.Intent(context, CameraActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!latch.await(30, TimeUnit.SECONDS)) {
            pending = null
            return FAILED to null
        }
        pending = null
        return out
    }

    fun answered(code: Int, bytes: ByteArray?) {
        pending?.invoke(code, bytes)
    }
}

/** Android's camera-permission ask, with nothing of the app around it (like LookActivity). */
class CameraActivity : Activity() {
    private val thread = HandlerThread("capri-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var done = false

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Lang.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open()
        else runCatching { requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1) }
            .onFailure { answer(CameraShot.DENIED, null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) open()
        else answer(CameraShot.DENIED, null)
    }

    private fun answer(code: Int, bytes: ByteArray?) {
        if (done) return
        done = true
        CameraShot.answered(code, bytes)
        thread.quitSafely()
        finish()
    }

    private fun open() {
        val manager = getSystemService(CameraManager::class.java)!!
        val id = manager.cameraIdList.firstOrNull { cid ->
            manager.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
        if (id == null) return answer(CameraShot.FAILED, null)
        // a modest size: enough for her to see by, kind to a slow AI upload
        val reader = ImageReader.newInstance(1280, 960, ImageFormat.JPEG, 2)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var warmed = false
        val close = {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { reader.close() }
        }
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            image.close()
            close()
            answer(CameraShot.OK, bytes)
        }, handler)
        handler.postDelayed({
            if (!done) { close(); answer(CameraShot.FAILED, null) } // no picture in 10 s: give up
        }, 10_000)
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    runCatching {
                        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                // a moment of preview-into-the-reader first, so the exposure settles
                                val warm = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                }.build()
                                runCatching { s.setRepeatingRequest(warm, null, handler) }
                                handler.postDelayed({
                                    if (done || warmed) return@postDelayed
                                    warmed = true
                                    runCatching { s.stopRepeating() }
                                    val shot = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                                    }.build()
                                    runCatching { s.capture(shot, null, handler) }
                                        .onFailure { close(); answer(CameraShot.FAILED, null) }
                                }, 900)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { close(); answer(CameraShot.FAILED, null) }
                        }, handler)
                    }.onFailure { close(); answer(CameraShot.FAILED, null) }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (!done) answer(CameraShot.FAILED, null) }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (!done) answer(CameraShot.FAILED, null) }
            }, handler)
        }.onFailure { close(); answer(CameraShot.FAILED, null) }
    }
}
