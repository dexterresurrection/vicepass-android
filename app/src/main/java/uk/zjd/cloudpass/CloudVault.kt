package uk.zjd.cloudpass

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.database.DatabaseTaskProvider.Companion.startDatabaseService
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Облачное хранилище: локальный файл files/cloud/vault.kdbx + синхронизация с pass.zjd.uk (docs/PROTOCOL.md).
 *
 * Состояние своё у каждого аккаунта:
 *   base, base_sha   версия сервера, которая учтена в открытой базе, и хеш файла на тот момент (локальные правки = хеш другой);
 *   pending          версия, скачанная в remote-<версия>.kdbx для слияния;
 *   reload, reload_sha  версия, которой заменили файл при открытой базе: базовой она станет, только когда база перечитается.
 * Отправка всегда с If-Match: base. Поэтому, что бы ни сохранилось в промежутке (правка во время замены файла, сбой,
 * перезапуск), сервер ответит 412 — и версии сольются, а не затрут друг друга.
 * Слияние — штатное KeePassDX (по записям, с ключом открытой базы). Без сети всё работает с локальным файлом.
 */
object CloudVault {
    private const val TAG = "CloudVault"
    private const val PREFS = "cloudpass_sync"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    enum class Opening { READY, NO_VAULT, OFFLINE, LOGIN }

    /** Облачная база сейчас открыта (ставят действия службы и закрытие базы). */
    @Volatile
    var open = false
        private set

    /** База закрыта (BroadcastAction.closeDatabase). */
    fun onClosed() { open = false }

    /** Подписчики на изменение состояния (экран «Аккаунт»). */
    val listeners = mutableSetOf<() -> Unit>()

    fun dir(ctx: Context) = File(ctx.filesDir, "cloud").apply { mkdirs() }
    fun file(ctx: Context) = File(dir(ctx), "vault.kdbx")
    private fun remoteFile(ctx: Context, v: Long) = File(dir(ctx), "remote-$v.kdbx")
    private fun remoteVersion(ctx: Context, uri: Uri?): Long = uri?.path?.let { File(it) }
        ?.takeIf { it.parentFile?.absolutePath == dir(ctx).absolutePath }
        ?.let { Regex("remote-(\\d+)\\.kdbx").matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() } ?: 0
    private fun dropRemote(ctx: Context) = dir(ctx).listFiles { f -> f.name.startsWith("remote-") }?.forEach { it.delete() }
    fun uri(ctx: Context): Uri = Uri.fromFile(file(ctx))

    fun isCloud(ctx: Context, uri: Uri?): Boolean =
        uri != null && uri.scheme == "file" && uri.path == file(ctx).absolutePath

    // ── состояние ──

    private fun root(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun account(ctx: Context): String = root(ctx).getString("account", "").orEmpty()
    private fun prefs(ctx: Context) = account(ctx).let { a ->
        if (a.isEmpty()) root(ctx) else ctx.applicationContext.getSharedPreferences("${PREFS}_${a.take(12)}", Context.MODE_PRIVATE)
    }

    fun base(ctx: Context) = prefs(ctx).getLong("base", 0)
    fun lastSync(ctx: Context) = prefs(ctx).getLong("last_sync", 0)
    fun lastError(ctx: Context): String = prefs(ctx).getString("last_error", "").orEmpty()
    private fun baseSha(ctx: Context) = prefs(ctx).getString("base_sha", "").orEmpty()
    private fun pending(ctx: Context) = prefs(ctx).getLong("pending", 0)
    private fun reload(ctx: Context) = prefs(ctx).getLong("reload", 0)
    private fun reloadSha(ctx: Context) = prefs(ctx).getString("reload_sha", "").orEmpty()

    private fun setState(ctx: Context, base: Long? = null, baseSha: String? = null, pending: Long? = null, reload: Long? = null,
                         reloadSha: String? = null, error: String? = null, synced: Boolean = false) {
        prefs(ctx).edit().apply {
            base?.let { putLong("base", it) }
            baseSha?.let { putString("base_sha", it) }
            pending?.let { putLong("pending", it) }
            reload?.let { putLong("reload", it) }
            reloadSha?.let { putString("reload_sha", it) }
            error?.let { putString("last_error", it) }
            if (synced) putLong("last_sync", System.currentTimeMillis())
        }.commit() // не apply: состояние не должно отстать от файла, если процесс убьют
        main.post { listeners.toList().forEach { it() } }
    }

    private fun hex(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun dirty(ctx: Context): Boolean = file(ctx).let { it.exists() && hex(it.readBytes()) != baseSha(ctx) }

    private fun device() = (Build.MANUFACTURER + " " + Build.MODEL).trim().take(60)

    private fun writeAtomic(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { it.write(data); it.fd.sync() }
        if (!tmp.renameTo(f)) throw java.io.IOException("файл хранилища не записался")
    }

    /** Файл целиком: во время сохранения ждём, пока база допишется. null — так и не дописался. */
    private fun readComplete(f: File): ByteArray? {
        repeat(12) {
            val b = f.readBytes()
            if (Kdbx.complete(b)) return b
            Thread.sleep(400)
        }
        return null
    }

    private fun downloaded(): Pair<PassMeta, ByteArray>? = PassApi.download()?.also { (_, b) ->
        if (!Kdbx.complete(b)) throw CabinetError(502, "Файл с сервера пришёл не целиком — попробуйте ещё раз")
    }

    /**
     * Чей это файл. Вошли в другой аккаунт — его хранилище своё: файл прежнего откладывается рядом (vault-<аккаунт>.kdbx)
     * и вернётся вместе с ним. Менять файл можно только при закрытой базе ([canSwap]).
     */
    private fun bind(ctx: Context, canSwap: Boolean) {
        val sub = PassApi.account()
        val cur = account(ctx)
        if (cur == sub) return
        if (cur.isNotEmpty()) {
            if (!canSwap) throw CabinetError(409, ctx.getString(R.string.cp_other_account))
            val f = file(ctx)
            if (f.exists() && !f.renameTo(File(dir(ctx), "vault-${cur.take(12)}.kdbx"))) throw java.io.IOException("файл хранилища занят")
            dropRemote(ctx)
            File(dir(ctx), "vault-${sub.take(12)}.kdbx").takeIf { it.exists() }?.renameTo(f)
        }
        root(ctx).edit().putString("account", sub).commit()
    }

    // ── открытие ──

    /**
     * Перед открытием (в фоне): подтянуть серверную версию, если она новее. [loaded] — база уже открыта, файл не трогаем.
     * READY — можно открывать файл; NO_VAULT — ни на устройстве, ни на сервере нет (предложить создать).
     */
    fun prepareOpen(ctx: Context, loaded: Boolean = false): Opening = synchronized(lock) { prepareLocked(ctx, loaded) }

    private fun prepareLocked(ctx: Context, loaded: Boolean): Opening {
        Cabinet.init(ctx)
        val f = file(ctx)
        if (loaded) return Opening.READY
        if (!Cabinet.loggedIn) return if (f.exists()) Opening.READY else Opening.LOGIN
        val m = try { bind(ctx, true); PassApi.meta() } catch (e: Exception) {
            setState(ctx, error = ctx.getString(R.string.cp_offline, e.message.orEmpty()))
            if (f.exists()) return Opening.OFFLINE
            throw e
        }
        setState(ctx, reload = 0, reloadSha = "")
        when {
            m == null && !f.exists() -> return Opening.NO_VAULT
            m == null || (m.version == base(ctx) && dirty(ctx)) -> push(ctx)
            !f.exists() || (m.version != base(ctx) && !dirty(ctx)) -> { // своего нет или правок нет — берём серверное
                val (meta, data) = downloaded() ?: return if (f.exists()) Opening.READY else Opening.NO_VAULT
                writeAtomic(f, data)
                setState(ctx, base = meta.version, baseSha = hex(data), pending = 0, error = "", synced = true)
            }
            m.version != base(ctx) -> { // правки с обеих сторон — сольём, когда база откроется
                val (meta, data) = downloaded() ?: return Opening.READY
                writeAtomic(remoteFile(ctx, meta.version), data)
                setState(ctx, pending = meta.version, error = "")
            }
            else -> setState(ctx, pending = 0, error = "", synced = true)
        }
        return Opening.READY
    }

    // ── отправка ──

    /** Отправить локальный файл (не главный поток). */
    fun push(ctx: Context) {
        synchronized(lock) { pushLocked(ctx) }
    }

    private fun pushLocked(ctx: Context) {
        val f = file(ctx)
        if (!Cabinet.loggedIn || !f.exists()) return
        try {
            bind(ctx, false)
            val data = readComplete(f) ?: throw CabinetError(500, ctx.getString(R.string.cp_incomplete))
            val sum = hex(data)
            if (sum == baseSha(ctx) && base(ctx) > 0) return
            if (reload(ctx) > 0 && sum == reloadSha(ctx)) return // это серверная версия, база её ещё перечитывает
            when (val r = PassApi.upload(data, base(ctx), device())) {
                is PutResult.Ok -> setState(ctx, base = r.meta.version, baseSha = sum, pending = 0, error = "", synced = true)
                is PutResult.Conflict -> if (r.current == null) { // на сервере хранилища больше нет — начинаем с первой версии
                    (PassApi.upload(data, 0, device()) as? PutResult.Ok)
                        ?.let { setState(ctx, base = it.meta.version, baseSha = sum, error = "", synced = true) }
                } else {
                    val (meta, remote) = downloaded() ?: return
                    writeAtomic(remoteFile(ctx, meta.version), remote)
                    setState(ctx, pending = meta.version, reload = 0, reloadSha = "", error = "")
                    main.post { startMerge(ctx) } // база закрыта — служба откажется (refused), сольём при следующем открытии
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "push", e)
            setState(ctx, error = e.message ?: ctx.getString(R.string.cp_sync_error))
        }
    }

    private fun startMerge(ctx: Context) {
        val rf = remoteFile(ctx, pending(ctx))
        if (!rf.exists()) return
        ctx.applicationContext.startDatabaseService(Bundle().apply {
            putBoolean(DatabaseTaskNotificationService.SAVE_DATABASE_KEY, true)
            putParcelable(DatabaseTaskNotificationService.DATABASE_URI_KEY, Uri.fromFile(rf))
        }, DatabaseTaskNotificationService.ACTION_DATABASE_MERGE_TASK)
    }

    /**
     * Служба спрашивает перед слиянием и перечитыванием: наше действие годится только для открытой облачной базы.
     * Пока шла сеть, человек мог закрыть её и открыть другую — в чужую базу серверную версию сливать нельзя.
     */
    fun refused(ctx: Context, openUri: Uri?, loaded: Boolean, sourceUri: Uri?): Boolean =
        remoteVersion(ctx, sourceUri) > 0 && !(loaded && isCloud(ctx, openUri))

    /** База прочитала файл (открытие или перечитывание): если это заменённая нами версия — она и становится базовой. */
    private fun adopted(ctx: Context) {
        val v = reload(ctx)
        if (v == 0L) return
        val sum = reloadSha(ctx)
        val same = file(ctx).let { it.exists() && hex(it.readBytes()) == sum }
        if (same) setState(ctx, base = v, baseSha = sum, reload = 0, reloadSha = "", error = "", synced = true)
        else setState(ctx, reload = 0, reloadSha = "") // файл успели переписать правкой: base прежний, дальше — слияние
    }

    /**
     * Из DatabaseTaskNotificationService после любого действия с базой (главный поток).
     * [sourceUri] — файл, который сливали (для слияния).
     */
    fun onAction(ctx: Context, fileUri: Uri?, action: String?, success: Boolean, saved: Boolean, sourceUri: Uri?) {
        val cloud = isCloud(ctx, fileUri)
        if (success && (action == DatabaseTaskNotificationService.ACTION_DATABASE_LOAD_TASK
                || action == DatabaseTaskNotificationService.ACTION_DATABASE_CREATE_TASK)) open = cloud
        if (!cloud) return
        val app = ctx.applicationContext
        when (action) {
            DatabaseTaskNotificationService.ACTION_DATABASE_LOAD_TASK -> if (success) {
                adopted(app)
                if (pending(app) > 0) startMerge(app)
            }
            DatabaseTaskNotificationService.ACTION_DATABASE_RELOAD_TASK -> {
                if (success) adopted(app) else setState(app, reload = 0, reloadSha = "", error = app.getString(R.string.cp_reload_failed))
                if (success) io.execute { push(app) }
                return
            }
            DatabaseTaskNotificationService.ACTION_DATABASE_MERGE_TASK -> {
                val v = remoteVersion(app, sourceUri)
                if (v > 0) {
                    if (success) {
                        remoteFile(app, v).delete()
                        if (v > base(app)) setState(app, base = v, error = "")
                        if (pending(app) > v) startMerge(app) // пока сливали, на сервере появилась ещё одна
                        else { setState(app, pending = 0); io.execute { push(app) } }
                    } else {
                        setState(app, error = app.getString(R.string.cp_merge_failed))
                    }
                    return
                }
            }
        }
        if (success && (saved || action == DatabaseTaskNotificationService.ACTION_DATABASE_CREATE_TASK))
            io.execute { push(app) }
    }

    private var lastPull = 0L

    /** База открыта и экран вернулся: если на сервере новее — без правок перечитать файл, с правками — слить. */
    fun pullIfChanged(ctx: Context, fileUri: Uri?) {
        val app = ctx.applicationContext
        if (!isCloud(app, fileUri) || System.currentTimeMillis() - lastPull < 30_000) return
        Cabinet.init(app)
        if (!Cabinet.loggedIn) return
        lastPull = System.currentTimeMillis()
        io.execute { pull(app) }
    }

    private fun pull(app: Context) {
        synchronized(lock) { pullLocked(app) }
    }

    private fun pullLocked(app: Context) {
        if (!open) { prepareLocked(app, false); return } // база закрыта — файл можно просто обновить
        try {
            bind(app, false)
            val m = PassApi.meta()
            if (m == null || m.version == base(app)) { if (m == null || dirty(app)) push(app) else setState(app, error = "", synced = true); return }
            if (reload(app) == m.version) return
            val (meta, data) = downloaded() ?: return
            if (dirty(app)) {
                writeAtomic(remoteFile(app, meta.version), data)
                setState(app, pending = meta.version, reload = 0, reloadSha = "")
                main.post { startMerge(app) }
            } else {
                setState(app, reload = meta.version, reloadSha = hex(data))
                writeAtomic(file(app), data)
                main.post { if (open) app.startDatabaseService(null, DatabaseTaskNotificationService.ACTION_DATABASE_RELOAD_TASK) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pull", e)
            setState(app, error = app.getString(R.string.cp_offline, e.message.orEmpty()))
        }
    }

    // ── экран «Аккаунт» ──

    fun syncNow(ctx: Context, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val err = try {
                if (file(app).exists()) { pull(app) } else prepareOpen(app)
                lastError(app).ifEmpty { null }
            } catch (e: Exception) { e.message ?: app.getString(R.string.cp_sync_error) }
            main.post { done(err) }
        }
    }

    /** Копия файла устройства перед тем, как его заменить: vault-local-<время>.kdbx рядом (последние 5). */
    private fun keepCopy(app: Context) {
        val f = file(app)
        if (!f.exists()) return
        f.copyTo(File(dir(app), "vault-local-${System.currentTimeMillis()}.kdbx"), overwrite = true)
        dir(app).listFiles { x -> x.name.startsWith("vault-local-") }?.sortedBy { it.name }?.dropLast(5)?.forEach { it.delete() }
    }

    /** Взять серверную версию целиком (файл устройства остаётся копией). База должна быть закрыта. */
    fun replaceLocalWithServer(ctx: Context, version: Long? = null) {
        synchronized(lock) { replaceLocalLocked(ctx, version) }
    }

    private fun replaceLocalLocked(ctx: Context, version: Long?) {
        val app = ctx.applicationContext
        bind(app, true)
        val cur = PassApi.meta()?.version ?: throw CabinetError(404, app.getString(R.string.cp_no_server_vault))
        val data = if (version == null) downloaded()?.second ?: throw CabinetError(404, app.getString(R.string.cp_no_server_vault))
        else PassApi.historyGet(version)
        if (!Kdbx.complete(data)) throw CabinetError(502, app.getString(R.string.cp_incomplete))
        keepCopy(app)
        writeAtomic(file(app), data)
        dropRemote(app)
        if (version == null) setState(app, base = cur, baseSha = hex(data), pending = 0, reload = 0, reloadSha = "", error = "", synced = true)
        else { // старая версия становится новой на сервере; нынешняя остаётся в истории
            setState(app, base = cur, baseSha = "", pending = 0, reload = 0, reloadSha = "", error = "")
            push(app)
        }
    }

    /** Записать на сервер файл с устройства поверх серверного (серверный остаётся в истории). */
    fun replaceServerWithLocal(ctx: Context) {
        synchronized(lock) { replaceServerLocked(ctx) }
    }

    private fun replaceServerLocked(ctx: Context) {
        val app = ctx.applicationContext
        bind(app, false)
        val cur = PassApi.meta()?.version ?: 0
        dropRemote(app)
        setState(app, base = cur, baseSha = "", pending = 0, reload = 0, reloadSha = "", error = "")
        push(app)
    }
}
