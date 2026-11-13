package com.ndxinvest.app.frontendv2

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject

/** App-private presentation preferences. Never calls an account or transaction service. */
internal class FrontendLocalUi(private val activity: ComponentActivity, private val emit: (String, JSONObject) -> Unit, private val theme: (Boolean, String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var palette = "classic"
    private val directory = File(activity.filesDir, "fund-card-wallpapers")
    private val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshInfo()
    }
    init { activity.lifecycle.addObserver(lifecycleObserver) }
    private var requestingNotifications = false
    private val notificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingNotifications = false
        refreshInfo()
        if (!granted) reply("settings-info", JSONObject().put("settingsError", "未允许通知。你可以稍后重试，或在系统通知设置中开启。"))
    }
    @JavascriptInterface fun requestNotifications() { activity.runOnUiThread {
        if (closed || requestingNotifications) return@runOnUiThread
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestingNotifications = true
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else if (!com.ndxinvest.app.notifications.NdxReminders.allowed(activity)) openSystemSettings("notifications")
        else refreshInfo()
    } }
    @JavascriptInterface fun setReminderEnabled(key: String, enabled: Boolean) { activity.runOnUiThread {
        if (!closed) { com.ndxinvest.app.notifications.NdxReminders.setEnabled(activity, key, enabled); refreshInfo() }
    } }
    @JavascriptInterface fun sendTestNotification() { activity.runOnUiThread {
        if (!closed) { val message = com.ndxinvest.app.notifications.NdxReminders.test(activity); refreshInfo(); reply("settings-info", JSONObject().put("notificationMessage", message)) }
    } }
    @JavascriptInterface fun configureReminderStrategy(tiers: String) {
        if (!closed && tiers.length <= 16000) worker.execute { com.ndxinvest.app.notifications.NdxReminders.configureStrategy(activity, tiers) }
    }
    @JavascriptInterface fun refreshInfo() { activity.runOnUiThread {
        if (closed) return@runOnUiThread
        val power = activity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        reply("settings-info", JSONObject()
            .put("versionName", com.ndxinvest.app.BuildConfig.VERSION_NAME)
            .put("versionCode", com.ndxinvest.app.BuildConfig.VERSION_CODE)
            .put("mode", com.ndxinvest.app.BuildConfig.FLAVOR)
            .put("debug", com.ndxinvest.app.BuildConfig.DEBUG)
            .put("androidVersion", android.os.Build.VERSION.RELEASE)
            .put("notificationsAllowed", androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled())
            .put("batteryUnrestricted", power.isIgnoringBatteryOptimizations(activity.packageName))
            .put("notificationDeliveryImplemented", true)
            .put("settingsError", "")
            .put("reminders", com.ndxinvest.app.notifications.NdxReminders.info(activity).getJSONObject("reminders")))
    } }
    @JavascriptInterface fun openSystemSettings(section: String) { activity.runOnUiThread {
        if (closed || section !in setOf("notifications", "battery")) return@runOnUiThread
        val intent = if (section == "notifications" && Build.VERSION.SDK_INT >= 26)
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
        else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + activity.packageName))
        try { activity.startActivity(intent) } catch (_: Exception) {
            reply("settings-info", JSONObject().put("settingsError", "无法打开系统设置，请在手机设置中查看 NDX Invest。"))
        }
    } }
    private val picker = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) { reply("picked", JSONObject()); return@registerForActivityResult }
        worker.execute {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri)) { decoder, info, _ ->
                    val scale = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                } else {
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0)
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
                    val raw = activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) } ?: error("image")
                    val orientation = if (Build.VERSION.SDK_INT >= 24) activity.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1 else 1
                    val matrix = android.graphics.Matrix().apply { when (orientation) { 2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f); 5 -> {setRotate(90f); postScale(-1f, 1f)}; 6 -> setRotate(90f); 7 -> {setRotate(270f); postScale(-1f, 1f)}; 8 -> setRotate(270f) } }
                    if (orientation == 1) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
                }
                val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out); out.toByteArray() }
                bitmap.recycle()
                reply("picked", JSONObject().put("image", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)))
            } catch (_: Exception) { reply("picked", JSONObject().put("error", "无法读取图片，请选择另一张图片。")) }
        }
    }
    private fun reply(event: String, data: JSONObject) { activity.runOnUiThread { if (!closed) emit(event, data) } }
    @JavascriptInterface fun setTheme(dark: Boolean) { activity.runOnUiThread { if (!closed) theme(dark, palette) } }
    @JavascriptInterface fun setAppearance(dark: Boolean, selectedPalette: String) { activity.runOnUiThread {
        if (!closed && selectedPalette in setOf("classic", "pink")) {
            palette = selectedPalette
            theme(dark, palette)
        }
    } }
    @JavascriptInterface fun choosePhoto() { activity.runOnUiThread { if (!closed) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
    private fun file(key: String): File { require(key.length in 1..160); val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }; return File(directory, "$hash.json") }
    @JavascriptInterface fun boot() { worker.execute { val result = JSONObject(); directory.listFiles()?.filter { it.extension == "json" }?.forEach { f -> try { val item = JSONObject(f.readText()); result.put(item.getString("key"), item.getString("image")) } catch (_: Exception) {} }; reply("loaded", result) } }
    @JavascriptInterface fun saveWallpaper(key: String, image: String) { worker.execute {
        try {
            val destination = file(key)
            if (image.isEmpty()) { if (destination.exists()) check(destination.delete()) }
            else {
                require(image.startsWith("data:image/jpeg;base64,") && image.length < 2_000_000)
                val bytes = Base64.decode(image.substringAfter(','), Base64.DEFAULT)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..1600 && bounds.outHeight in 1..1600)
                directory.mkdirs()
                val atomic = android.util.AtomicFile(destination)
                val out = atomic.startWrite()
                try { out.write(JSONObject().put("key", key).put("image", image).toString().toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
            }
            reply("saved", JSONObject().put("key", key).put("image", image))
        } catch (_: Exception) { reply("saved", JSONObject().put("error", "壁纸保存失败，请重试。")) }
    } }
    fun close() { closed = true; activity.lifecycle.removeObserver(lifecycleObserver); worker.shutdown(); picker.unregister(); notificationPermission.unregister() }
}
