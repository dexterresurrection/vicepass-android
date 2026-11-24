package uk.zjd.cloudpass

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.kunzisoft.keepass.BuildConfig
import com.kunzisoft.keepass.R
import uk.zjd.cvdesign.CvApps
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обновление внутри приложения (образец — Cloud VPN 2.21): downloads.json → файл СВОЕЙ линии (pass / vice-pass) →
 * скачивание с докачкой (РУ Зеркало → GitHub → наш сервер, .part продолжается после обрыва и перезапуска) → sha256 из
 * downloads.json → PackageInstaller (система спросит подтверждение). Разрешение «устанавливать из этого источника»
 * ждём и продолжаем сами. Пакет и ключ подписи те же: данные и вход сохраняются, чужая линия не придёт.
 */
object Updater {
    private const val TAG = "CloudPassUpdater"
    private val io = Executors.newSingleThreadExecutor()

    class Update(val version: String, val name: String, val sha256: String, val size: Long,
                 val urls: List<Pair<String, String>>, val notes: String)

    private class Cancelled : Exception()

    /** Для проверки в эмуляторе: `adb shell setprop debug.cloudpass.downloads <url>` (без adb не задать). */
    private fun testSource(): String = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.cloudpass.downloads") as String).trim()
    } catch (_: Exception) { "" }

    fun newer(a: String, b: String): Boolean {
        val x = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Новая версия своей линии или null (не главный поток). */
    @Suppress("UNCHECKED_CAST")
    fun check(ctx: Context, force: Boolean): Update? {
        val test = testSource()
        val cat = CvApps.load(File(ctx.cacheDir, "cv-apps/downloads.json"), force = force || test.isNotEmpty(),
            sources = if (test.isNotEmpty()) listOf(test) else CvApps.SOURCES) ?: return null
        val brand = Brand.get(ctx)
        val p = cat.products[brand.product] ?: return null
        val ver = p["version"] as? String ?: return null
        if (!newer(ver, BuildConfig.VERSION_NAME)) return null
        val f = (p["files"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }
            .filter { (it["name"] as? String)?.endsWith(".apk") == true }
            .let { l -> l.firstOrNull { it["flavor"] == brand.flavor } ?: l.firstOrNull() } ?: return null
        val urls = listOfNotNull((f["bucket"] as? String)?.let { "РУ Зеркало" to it }, (f["github"] as? String)?.let { "GitHub" to it },
            (f["server"] as? String)?.let { "Наш сервер" to it })
        val sha = (f["sha256"] as? String).orEmpty().lowercase()
        if (urls.isEmpty() || sha.length != 64) return null
        return Update(ver, f["name"] as? String ?: "update.apk", sha, (f["size"] as? Number)?.toLong() ?: 0, urls, p["notes"] as? String ?: "")
    }

    /** Раз в сутки (или по кнопке) предложить обновление. [nothing] — новой версии нет. */
    fun offer(activity: Activity, force: Boolean = false, nothing: (() -> Unit)? = null) {
        val prefs = activity.getSharedPreferences("cloudpass_update", Context.MODE_PRIVATE)
        if (!force && testSource().isEmpty() && System.currentTimeMillis() - prefs.getLong("asked", 0) < 86400_000L) return
        io.execute {
            val u = runCatching { check(activity, force) }.onFailure { Log.w(TAG, "check", it) }.getOrNull()
            activity.runOnUiThread {
                if (!alive(activity)) return@runOnUiThread
                if (u == null) { nothing?.invoke(); return@runOnUiThread }
                prefs.edit().putLong("asked", System.currentTimeMillis()).apply()
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.cp_update_title, Brand.get(activity).name, u.version))
                    .setMessage(u.notes.ifEmpty { activity.getString(R.string.cp_update_text) })
                    .setPositiveButton(R.string.cp_update_now) { _, _ -> start(activity, u) }
                    .setNeutralButton(R.string.cp_update_links) { _, _ -> links(activity, u) }
                    .setNegativeButton(R.string.cp_later, null)
                    .show()
            }
        }
    }

    private fun alive(a: Activity) = !a.isFinishing && !a.isDestroyed

    private fun links(activity: Activity, u: Update) {
        AlertDialog.Builder(activity).setTitle(u.name)
            .setItems(u.urls.map { it.first }.toTypedArray()) { _, i ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.urls[i].second))) }
            }.show()
    }

    private fun fail(activity: Activity, u: Update, message: String) = activity.runOnUiThread {
        if (alive(activity)) AlertDialog.Builder(activity).setTitle(R.string.cp_update_not_done).setMessage(message)
            .setPositiveButton(R.string.cp_update_retry) { _, _ -> start(activity, u) }
            .setNeutralButton(R.string.cp_update_links) { _, _ -> links(activity, u) }
            .setNegativeButton(R.string.cp_later, null).show()
    }

    private fun start(activity: Activity, u: Update) {
        val cancelled = AtomicBoolean(false)
        val d = activity.resources.displayMetrics.density
        val text = TextView(activity).apply { setText(R.string.cp_update_wait) }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), 0)
            addView(text); addView(bar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val dlg = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.cp_update_downloading, u.version)).setView(box)
            .setCancelable(false).setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }.show()
        fun close() = activity.runOnUiThread { if (alive(activity)) runCatching { dlg.dismiss() } }
        io.execute {
            try {
                if (!allowed(activity)) {
                    activity.runOnUiThread { text.setText(R.string.cp_update_allow) }
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    }
                    val until = System.currentTimeMillis() + 5 * 60_000
                    while (!allowed(activity)) { // человек в настройках: продолжим сами, как только разрешит
                        if (cancelled.get()) throw Cancelled()
                        if (System.currentTimeMillis() > until) throw IllegalStateException(activity.getString(R.string.cp_update_no_permission))
                        Thread.sleep(700)
                    }
                }
                val apk = download(activity, u, { cancelled.get() }) { done, total ->
                    activity.runOnUiThread {
                        bar.isIndeterminate = total <= 0
                        if (total > 0) bar.progress = (done * 1000 / total).toInt()
                        text.text = activity.getString(R.string.cp_update_progress, done / 1048576.0, total / 1048576.0)
                    }
                }
                close()
                install(activity, apk)
            } catch (_: Cancelled) {
                close()
            } catch (e: Exception) {
                Log.w(TAG, "update", e)
                close()
                fail(activity, u, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    private fun open(url: URL, from: Long) = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false
        setRequestProperty("User-Agent", Net.UA)
        setRequestProperty("Accept-Encoding", "identity")
        if (from > 0) setRequestProperty("Range", "bytes=$from-")
    }

    /** Файл годен, только если sha256 совпал; недокачанное остаётся в .part и продолжается с любого адреса (файл везде один). */
    fun download(ctx: Context, u: Update, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "${u.sha256.take(16)}.apk")
        val part = File(dir, "${u.sha256.take(16)}.part")
        dir.listFiles()?.filter { it != out && it != part }?.forEach { it.delete() }
        if (out.exists() && sha(out) == u.sha256) return out
        out.delete()
        val why = LinkedHashMap<String, String>()
        repeat(3) {
            for ((label, url) in u.urls) {
                if (cancelled()) throw Cancelled()
                try {
                    fetch(URL(url), part, u.size, cancelled, progress)
                    if (sha(part) == u.sha256) {
                        if (!part.renameTo(out)) throw java.io.IOException(ctx.getString(R.string.cp_update_no_space))
                        return out
                    }
                    part.delete()
                    why[label] = ctx.getString(R.string.cp_update_bad_file)
                } catch (e: Cancelled) {
                    throw e
                } catch (e: Exception) {
                    why[label] = when (e) {
                        is java.net.UnknownHostException -> ctx.getString(R.string.cp_update_no_host)
                        is java.net.SocketTimeoutException -> ctx.getString(R.string.cp_update_timeout)
                        else -> e.message ?: e.javaClass.simpleName
                    }
                }
            }
        }
        throw IllegalStateException(ctx.getString(R.string.cp_update_failed_download) + "\n" + why.entries.joinToString("\n") { "• ${it.key}: ${it.value}" })
    }

    private fun fetch(start: URL, part: File, size: Long, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        if (size > 0 && part.length() == size) return
        if (size > 0 && part.length() > size) part.delete()
        var from = part.length()
        var cur = start
        var c = open(cur, from)
        try {
            var hops = 0
            while (c.responseCode in 300..399 && hops++ < 6) { // GitHub → хранилище, http → https
                val next = c.getHeaderField("Location") ?: break
                c.disconnect()
                cur = URL(cur, next)
                c = open(cur, from)
            }
            val code = c.responseCode
            if (code == 416) { part.delete(); throw java.io.IOException("ответ 416") }
            if (code != 200 && code != 206) throw java.io.IOException("ответ $code")
            if (code == 200) from = 0 // сервер не умеет докачку — сначала
            val total = if (size > 0) size else from + c.contentLength.toLong()
            RandomAccessFile(part, "rw").use { f ->
                f.setLength(from)
                f.seek(from)
                c.inputStream.use { inp ->
                    val b = ByteArray(1 shl 16)
                    var done = from
                    var shown = 0L
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = inp.read(b)
                        if (n < 0) break
                        f.write(b, 0, n)
                        done += n
                        if (System.currentTimeMillis() - shown > 200) { shown = System.currentTimeMillis(); progress(done, total) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (size > 0 && part.length() < size) throw java.io.IOException("оборвалось на ${part.length() * 100 / size} %")
    }

    private fun sha(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        pi.mySessions.forEach { runCatching { pi.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    /** Ответ установщика: показать окно подтверждения системы или понятную причину отказа. */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return
            }
            if (st == PackageInstaller.STATUS_SUCCESS) return
            val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            Log.w(TAG, "install status $st $detail")
            val res = when (st) {
                PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.cp_install_aborted
                PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.cp_install_storage
                PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.cp_install_conflict
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.cp_install_incompatible
                PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.cp_install_blocked
                else -> R.string.cp_install_failed
            }
            Toast.makeText(context, context.getString(res), Toast.LENGTH_LONG).show()
        }
    }
}
