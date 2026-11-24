package uk.zjd.cloudpass.importer

import android.content.Context
import android.net.Uri
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.action.node.ActionNodeDatabaseRunnable
import com.kunzisoft.keepass.database.action.node.ActionNodesValues
import com.kunzisoft.keepass.database.action.node.AfterActionNodesFinish
import com.kunzisoft.keepass.database.element.Group
import com.kunzisoft.keepass.database.element.node.NodeId
import com.kunzisoft.keepass.database.element.security.ProtectedString
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.database.element.Field
import com.kunzisoft.keepass.otp.OtpEntryFields.setOtp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Импорт в открытую базу (Cloud Pass): читает файл [sourceUri], создаёт группу «Импорт <дата>» в корне,
 * внутри — подгруппы по папкам источника, записи с логином, паролем, адресом, заметкой, кодом 2FA и доп. полями.
 * Сохраняет базу (а сохранение облачной базы само уходит на сервер).
 */
class ImportEntriesRunnable(
    context: Context,
    database: ContextualDatabase,
    private val sourceUri: Uri,
    private val fileName: String,
    save: Boolean,
    afterActionNodesFinish: AfterActionNodesFinish?,
    challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
) : ActionNodeDatabaseRunnable(context, database, afterActionNodesFinish, save, challengeResponseRetriever) {

    var imported = 0
        private set
    private var newGroupIds = mutableListOf<NodeId<*>>()

    override fun nodeAction() {
        val data = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
            ?: throw ImportException("Файл не открылся")
        val items = Importer.parse(fileName, data)
        if (items.isEmpty()) throw ImportException("В файле нет записей")
        val root = database.rootGroup ?: throw ImportException("База не открыта")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        val top = newGroup("Импорт $stamp", root)
        val folders = HashMap<String, Group>()
        for (it in items) {
            val parent = if (it.folder.isBlank()) top else folders.getOrPut(it.folder.trim()) { newGroup(it.folder.trim(), top) }
            val info = EntryInfo().apply {
                title = it.title
                username = it.username
                password = it.password.toCharArray()
                url = it.url
                notes = it.notes
                it.extra.forEachIndexed { i, (name, value) ->
                    val n = name.ifBlank { "Поле ${i + 1}" }
                    var unique = n
                    var k = 2
                    while (customFields.any { f -> f.name == unique } || unique in RESERVED) unique = "$n ($k)".also { k++ }
                    customFields.add(Field(unique, ProtectedString(looksSecret(n), value)))
                }
                Importer.otpUri(it.otp, it.title, it.username)?.let { uri -> setOtp(uri) }
            }
            val entry = database.createEntry(info) ?: continue
            entry.touch(modified = true, touchParents = false)
            database.addEntryTo(entry, parent)
            imported++
        }
        top.touch(modified = true, touchParents = true)
    }

    private fun newGroup(title: String, parent: Group): Group {
        val g = database.createGroup() ?: throw ImportException("Не удалось создать группу")
        g.title = title
        g.touch(modified = true, touchParents = false)
        database.addGroupTo(g, parent)
        newGroupIds.add(g.nodeId)
        return g
    }

    override fun nodeFinish(): ActionNodesValues = ActionNodesValues(newGroupsIds = newGroupIds)

    companion object {
        private val RESERVED = setOf("Title", "UserName", "Password", "URL", "Notes", "otp")
        private fun looksSecret(name: String): Boolean {
            val n = name.lowercase(Locale.ROOT)
            return listOf("pass", "парол", "pin", "cvv", "code", "secret", "секрет", "token", "key", "ключ").any { it in n }
        }
    }
}
