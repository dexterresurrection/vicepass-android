package uk.zjd.cloudpass

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.stylish.StylishActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * «Аккаунт и облако»: вход через кабинет (код устройства), состояние синхронизации, версии на сервере,
 * выход из конфликта, проверка обновлений. Внизу — плашки «Наши приложения» / «Vice apps» и реклама
 * (здесь нет ни одного пароля). Мастер-пароль здесь не спрашивается и никуда не отправляется.
 */
class CloudAccountActivity : StylishActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var account: TextView
    private lateinit var loginBtn: Button
    private lateinit var col: LinearLayout
    private val listener: () -> Unit = { render() }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit): Button =
        MaterialButton(this, null, if (primary) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cabinet.init(this)
        val brand = Brand.get(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        col.addView(text(22f, true).apply { text = brand.name })
        col.addView(text(14f).apply { text = getString(R.string.cp_account_intro, brand.serviceName) })
        account = text(15f, true)
        col.addView(account)
        status = text(14f)
        col.addView(status)
        loginBtn = button("", primary = true) { if (Cabinet.loggedIn) confirmLogout() else login() }
        col.addView(loginBtn)
        col.addView(button(getString(R.string.cp_sync_now)) { syncNow() })
        col.addView(button(getString(R.string.cp_history)) { history() })
        col.addView(button(getString(R.string.cp_conflict)) { conflict() })
        col.addView(button(getString(R.string.cp_check_update)) {
            Updater.offer(this, force = true) { toast(getString(R.string.cp_no_update)) }
        })
        col.addView(text(12f).apply { text = getString(R.string.cp_privacy_note); alpha = 0.8f })
        // плашки — только здесь и на экране выбора хранилища
        col.addView(Plates.appsPlate(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        })
        val adBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        col.addView(adBox)
        Plates.showAd(adBox)
        val bar = com.google.android.material.appbar.MaterialToolbar(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(bar, com.google.android.material.R.attr.colorSurface))
            addView(bar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(ScrollView(this@CloudAccountActivity).apply {
                setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(bar, android.R.attr.colorBackground))
                clipToPadding = false
                addView(col)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // вырезы, скругления и системные полосы (Android 15+ рисует приложение от края до края)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(bar)
        title = getString(R.string.cp_account_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        render()
        if (Cabinet.loggedIn) io.execute {
            val name = Cabinet.accountName()
            main.post { if (name.isNotEmpty()) account.text = getString(R.string.cp_account_name, name) }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        Themes.addTo(this, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onStart() { super.onStart(); CloudVault.listeners.add(listener); render() }
    override fun onStop() { CloudVault.listeners.remove(listener); super.onStop() }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    private fun render() {
        val logged = Cabinet.loggedIn
        loginBtn.text = getString(if (logged) R.string.cp_logout else R.string.cp_login)
        if (!logged) account.text = getString(R.string.cp_not_logged)
        else if (account.text.isNullOrEmpty() || account.text == getString(R.string.cp_not_logged)) account.text = getString(R.string.cp_logged)
        val parts = mutableListOf<String>()
        val f = CloudVault.file(this)
        parts += if (f.exists()) getString(R.string.cp_local_vault, android.text.format.Formatter.formatShortFileSize(this, f.length()))
        else getString(R.string.cp_no_local_vault)
        val v = CloudVault.base(this)
        if (v > 0) parts += getString(R.string.cp_server_version, v)
        val last = CloudVault.lastSync(this)
        if (last > 0) parts += getString(R.string.cp_last_sync, DateUtils.getRelativeTimeSpanString(last))
        if (f.exists() && CloudVault.dirty(this)) parts += getString(R.string.cp_unsent)
        CloudVault.lastError(this).takeIf { it.isNotEmpty() }?.let { parts += "⚠ $it" }
        status.text = parts.joinToString("\n")
    }

    // ── вход по коду устройства ──

    private fun login() {
        io.execute {
            val code = try { Cabinet.deviceRequest() } catch (e: Exception) {
                main.post { toast(e.message ?: "Ошибка") }; return@execute
            }
            main.post { showCode(code) }
        }
    }

    private fun showCode(code: DeviceCode) {
        var alive = true
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(text(14f).apply { text = getString(R.string.cp_code_hint) })
        box.addView(text(30f, true).apply { text = code.userCode; gravity = Gravity.CENTER; setTextIsSelectable(true) })
        box.addView(button(getString(R.string.cp_open_cabinet), primary = true) { open(code.verificationUrl) })
        code.telegramUrl?.let { url -> box.addView(button(getString(R.string.cp_open_telegram)) { open(url) }) }
        val dlg = AlertDialog.Builder(this).setTitle(R.string.cp_login).setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> alive = false }
            .setOnDismissListener { alive = false }
            .show()
        val until = System.currentTimeMillis() + code.expiresIn * 1000L
        io.execute {
            while (alive && System.currentTimeMillis() < until) {
                Thread.sleep(code.interval * 1000L)
                if (!alive) return@execute
                when (try { Cabinet.devicePoll(code.token) } catch (e: Exception) { main.post { toast(e.message ?: "") }; PollState.EXPIRED }) {
                    PollState.DONE -> {
                        // сразу проверим, пускает ли сервер хранилища
                        val err = try { PassApi.forget(); PassApi.meta(); null } catch (e: Exception) { e.message }
                        main.post {
                            dlg.dismiss()
                            toast(err?.let { getString(R.string.cp_login_ok_but, it) } ?: getString(R.string.cp_login_ok))
                            render()
                            io.execute { val n = Cabinet.accountName(); main.post { if (n.isNotEmpty()) account.text = getString(R.string.cp_account_name, n) } }
                        }
                        return@execute
                    }
                    PollState.EXPIRED -> { main.post { dlg.dismiss(); toast(getString(R.string.cp_code_expired)) }; return@execute }
                    PollState.PENDING -> {}
                }
            }
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this).setMessage(R.string.cp_logout_confirm)
            .setPositiveButton(R.string.cp_logout) { _, _ -> Cabinet.logout(); account.text = ""; render() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ── синхронизация, версии, конфликт ──

    private fun needLogin(): Boolean {
        if (Cabinet.loggedIn) return false
        toast(getString(R.string.cp_need_login)); return true
    }

    private fun syncNow() {
        if (needLogin()) return
        status.text = getString(R.string.cp_syncing)
        CloudVault.syncNow(this) { err -> render(); toast(err ?: getString(R.string.cp_synced)) }
    }

    private fun history() {
        if (needLogin()) return
        io.execute {
            val list = try { PassApi.history() } catch (e: Exception) { main.post { toast(e.message ?: "") }; return@execute }
            main.post {
                if (list.isEmpty()) { toast(getString(R.string.cp_history_empty)); return@post }
                val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                val names = list.map { "v${it.version} · ${fmt.format(Date(it.updated * 1000))}" + (if (it.device.isNotEmpty()) " · ${it.device}" else "") }
                AlertDialog.Builder(this).setTitle(R.string.cp_history)
                    .setItems(names.toTypedArray()) { _, i -> confirmRestore(list[i].version) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
    }

    /** Закрыть открытую базу (файл заменяется целиком) — тот же сигнал, что у кнопки «Заблокировать». */
    private fun closeDatabase() {
        sendBroadcast(Intent(com.kunzisoft.keepass.utils.LOCK_ACTION).setPackage(packageName))
    }

    private fun confirmRestore(version: Long) {
        AlertDialog.Builder(this).setMessage(getString(R.string.cp_restore_confirm, version))
            .setPositiveButton(R.string.cp_restore) { _, _ ->
                closeDatabase()
                io.execute {
                    val err = try { CloudVault.replaceLocalWithServer(this, version); null } catch (e: Exception) { e.message }
                    main.post { render(); toast(err ?: getString(R.string.cp_restored, version)) }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun conflict() {
        if (needLogin()) return
        AlertDialog.Builder(this).setTitle(R.string.cp_conflict)
            .setMessage(R.string.cp_conflict_text)
            .setPositiveButton(R.string.cp_take_server) { _, _ ->
                closeDatabase()
                io.execute {
                    val err = try { CloudVault.replaceLocalWithServer(this); null } catch (e: Exception) { e.message }
                    main.post { render(); toast(err ?: getString(R.string.cp_done)) }
                }
            }
            .setNeutralButton(R.string.cp_send_local) { _, _ ->
                io.execute {
                    val err = try { CloudVault.replaceServerWithLocal(this); CloudVault.lastError(this).ifEmpty { null } } catch (e: Exception) { e.message }
                    main.post { render(); toast(err ?: getString(R.string.cp_done)) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
