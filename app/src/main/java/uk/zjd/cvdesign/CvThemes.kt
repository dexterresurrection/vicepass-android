// Темы экосистемы и правило «тему модуля задаёт хост» (без android.* и Compose — общее для View, Compose и Desktop).
//
// Пять тем, идентификаторы одни для всех программ, кабинета и модулей; порядок переключения общий:
//   dark → ff7 → vice-night → vice-day → light → dark
//
// Хост и модули. Хост — программа, в которой человек сейчас находится; модуль — всё, что открыто внутри неё: экран
// другого нашего приложения, встроенная почта, кабинет, кошелёк, мессенджер (Activity, Fragment, Composable, WebView).
//   1. Тема одна — хоста. Модуль берёт CvHostTheme.resolve("wallet", своя), а не свою настройку.
//   2. Модуль НЕ сохраняет тему хоста как свою: запущенный отдельно, он снова в своей теме.
//   3. В модуле нет кнопки смены темы; сменили тему в хосте — модуль меняется сразу.
//   4. Нижнее меню, листы, диалоги модуль рисует ролями темы (surfaceContainer и т. п.), а не своими цветами.
// Имя ("vpn", "wallet", "mail"…) — по нему экран понимает, в своей он программе или модулем в чужой.
// Хост:   в Application.onCreate и при каждой смене — CvHostTheme.set("vpn", theme). Другой процесс — Intent-extra
//         CvHostTheme.EXTRA; WebView и <iframe> — ?cv_theme= в адресе (CvHostTheme.withTheme) и при смене
//         evaluateJavascript("window.CvTheme&&CvTheme.follow('" + theme.id + "')", null).
// Модуль: val theme = CvHostTheme.resolve("wallet", prefs.theme); Activity, которую открыло другое наше приложение, —
//         CvHostTheme.follow(CvTheme.byId(intent.getStringExtra(CvHostTheme.EXTRA))).
package uk.zjd.cvdesign

import java.util.concurrent.CopyOnWriteArrayList

enum class CvTheme(val id: String, val ru: String, val en: String, val dark: Boolean, val vice: Boolean) {
    DARK("dark", "Тёмная", "Dark", true, false),
    FF7("ff7", "Классика RPG", "Classic RPG", true, false),
    VICE_NIGHT("vice-night", "Vice ночь", "Vice night", true, true),
    VICE_DAY("vice-day", "Vice день", "Vice day", false, true),
    LIGHT("light", "Светлая", "Light", false, false);

    fun title(russian: Boolean) = if (russian) ru else en

    companion object {
        /** Порядок переключения — как в кабинете и на Windows. */
        val ORDER: List<CvTheme> = values().toList()

        fun byId(id: String?): CvTheme? = ORDER.firstOrNull { it.id == id }

        /** Следующая тема по кругу среди [allowed]. */
        fun next(cur: CvTheme, allowed: List<CvTheme> = ORDER): CvTheme {
            val list = ORDER.filter { it in allowed }
            if (list.isEmpty()) return cur
            val i = ORDER.indexOf(cur)
            for (k in 1..ORDER.size) ORDER[(i + k) % ORDER.size].let { if (it in list) return it }
            return cur
        }
    }
}

/** Тема хоста для модулей этого процесса. Менять — из главного потока. */
object CvHostTheme {
    /** Intent-extra с идентификатором темы хоста (String). */
    const val EXTRA = "cv_theme"
    /** Параметр адреса для WebView и <iframe>; почта понимает и старый theme. */
    const val PARAM = "cv_theme"

    /** Программа-хост этого процесса и её тема. */
    @Volatile
    var owner: String? = null
        private set
    @Volatile
    private var theme: CvTheme? = null
    /** Тема приложения, которое открыло этот экран из другого процесса. */
    @Volatile
    private var followed: CvTheme? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Программа [me] объявляет свою тему. Хост — первый объявивший (Application); чужих потом не слушаем. */
    fun set(me: String, t: CvTheme) {
        if (owner != null && owner != me) return
        owner = me
        if (theme == t) return
        theme = t
        fire()
    }

    /** Экран открыло другое наше приложение в своей теме; null — снова своя. */
    fun follow(t: CvTheme?) {
        if (followed == t) return
        followed = t
        fire()
    }

    /** [me] сейчас модуль: тему ему задаёт хост (своей кнопки темы быть не должно). */
    fun hosted(me: String): Boolean = followed != null || (owner != null && owner != me)

    /** Тема для [me]: хоста, если это модуль, иначе [own]. */
    fun resolve(me: String, own: CvTheme): CvTheme = followed ?: (if (owner != null && owner != me) theme else null) ?: own

    private fun fire() { for (l in listeners) l() }

    /** Следить за сменой темы хоста; вернёт отписку. */
    fun onChange(fn: () -> Unit): () -> Unit {
        listeners.add(fn)
        return { listeners.remove(fn) }
    }

    /** Адрес модуля с темой хоста (прежнее значение параметра заменяется). */
    fun withTheme(url: String, t: CvTheme): String {
        val hash = url.indexOf('#').let { if (it < 0) "" else url.substring(it) }
        var base = if (hash.isEmpty()) url else url.substring(0, url.length - hash.length)
        base = base.replace(Regex("([?&])$PARAM=[^&]*&?")) { it.groupValues[1] }.trimEnd('&', '?')
        return base + (if ('?' in base) '&' else '?') + PARAM + "=" + t.id + hash
    }
}
