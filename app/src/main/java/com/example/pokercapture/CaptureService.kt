package com.example.pokercapture

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "capture.start"
        const val ACTION_STOP = "capture.stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val CHANNEL = "capture"
    }
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var lastSignature: Long? = null
    private var lastSavedAt = 0L
    private var tableWasPresent = false
    private var heroActionWasVisible = false
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_START) {
            startForeground(1, NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Poker Capture").setContentText("Screen capture active")
                .setSmallIcon(android.R.drawable.ic_menu_camera).build())
            startCapture(intent)
        }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun startCapture(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent = if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)!! else intent.getParcelableExtra(EXTRA_DATA)!!
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                reader?.close()
                reader = null
                projection = null
                stopSelf()
            }
        }
        projection!!.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        projection!!.createVirtualDisplay("PokerCapture", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        reader!!.setOnImageAvailableListener({ r -> processFrame(r) }, null)
    }
    private fun processFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bmp = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            val clean = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            bmp.recycle()
            val x0 = (clean.width * 0.08).toInt(); val x1 = (clean.width * 0.92).toInt()
            val y0 = (clean.height * 0.18).toInt(); val y1 = (clean.height * 0.72).toInt()
            var sig = 0L; var samples = 0; var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { sig += clean.getPixel(x, y).toLong() and 0x00FFFFFF; samples++; x += 48 }
                y += 48
            }
            sig /= samples.coerceAtLeast(1)
            val now = System.currentTimeMillis()
            // Guard: only save when the PokerStars table is likely visible.
            // Detect the stable green felt in the central table area; navigating to other apps should fail this test.
            var green = 0
            var total = 0
            var gy = (clean.height * 0.28).toInt()
            val gy1 = (clean.height * 0.68).toInt()
            while (gy < gy1) {
                var gx = (clean.width * 0.12).toInt()
                val gx1 = (clean.width * 0.88).toInt()
                while (gx < gx1) {
                    val p = clean.getPixel(gx, gy)
                    val rr = (p shr 16) and 255
                    val gg = (p shr 8) and 255
                    val bb = p and 255
                    if (gg > rr * 1.18 && gg > bb * 1.12 && gg > 45) green++
                    total++
                    gx += 32
                }
                gy += 32
            }
            val tablePresent = total > 0 && green.toDouble() / total > 0.18

            // Hero-turn trigger: the poker client shows large bright action buttons
            // in the bottom action area only when Hero can act. Detect that region
            // and save once on the transition hidden -> visible.
            var actionLike = 0
            var actionTotal = 0
            var ay = (clean.height * 0.78).toInt()
            val ay1 = (clean.height * 0.94).toInt()
            while (ay < ay1) {
                var ax = (clean.width * 0.05).toInt()
                val ax1 = (clean.width * 0.95).toInt()
                while (ax < ax1) {
                    val p = clean.getPixel(ax, ay)
                    val rr = (p shr 16) and 255
                    val gg = (p shr 8) and 255
                    val bb = p and 255
                    val maxc = maxOf(rr, gg, bb)
                    val minc = minOf(rr, gg, bb)
                    if (maxc > 105 && (maxc - minc > 35 || rr + gg + bb > 480)) actionLike++
                    actionTotal++
                    ax += 20
                }
                ay += 20
            }
            val heroActionVisible = tablePresent && actionTotal > 0 &&
                actionLike.toDouble() / actionTotal > 0.055

            if (heroActionVisible && !heroActionWasVisible && now - lastSavedAt > 900) {
                saveFrame(clean, now)
                lastSavedAt = now
                sendBroadcast(Intent("com.example.pokercapture.FRAME_SAVED").setPackage(packageName))
            }
            heroActionWasVisible = heroActionVisible
            tableWasPresent = tablePresent
            if (tablePresent) lastSignature = sig
            clean.recycle()
        } finally { image.close() }
    }
    private fun saveFrame(bitmap: Bitmap, ts: Long) {
        val dir = File(getExternalFilesDir(null), "frames").apply { mkdirs() }
        FileOutputStream(File(dir, "frame_$ts.jpg")).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }
    private fun stopCapture() {
        reader?.close(); reader = null
        projectionCallback?.let { callback -> projection?.unregisterCallback(callback) }
        projectionCallback = null
        projection?.stop(); projection = null
    }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
}
