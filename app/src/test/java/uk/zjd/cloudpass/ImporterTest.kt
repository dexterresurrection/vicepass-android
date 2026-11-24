package uk.zjd.cloudpass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.zjd.cloudpass.importer.ImportException
import uk.zjd.cloudpass.importer.ImportFormat
import uk.zjd.cloudpass.importer.Importer
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImporterTest {
    @Test fun chromeCsv() {
        val csv = "﻿name,url,username,password,note\r\n" +
            "example.com,https://example.com/login,alice,\"p,a\"\"ss\",\"line1\nline2\"\r\n" +
            ",https://www.mail.ru/,bob,secret,\r\n"
        val items = Importer.parse("Chrome Passwords.csv", csv.toByteArray())
        assertEquals(2, items.size)
        assertEquals("example.com", items[0].title)
        assertEquals("p,a\"ss", items[0].password)
        assertEquals("line1\nline2", items[0].notes)
        assertEquals("mail.ru", items[1].title)
    }

    @Test fun firefoxCsv() {
        val csv = "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\n" +
            "\"https://github.com\",\"carol\",\"gh-pass\",,\"https://github.com\",\"{abc}\",\"1\",\"2\",\"3\"\n"
        val it = Importer.parse("logins.csv", csv.toByteArray()).single()
        assertEquals("github.com", it.title)
        assertEquals("carol", it.username)
        assertTrue(it.extra.isEmpty())
    }

    @Test fun bitwardenCsvAndKeePassXcCsv() {
        val bw = "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n" +
            "Work,,login,Jira,,\"PIN: 1234\",0,https://jira.example,dave,jpass,JBSWY3DPEHPK3PXP\n"
        val a = Importer.parse("bitwarden.csv", bw.toByteArray()).single()
        assertEquals("Work", a.folder)
        assertEquals("JBSWY3DPEHPK3PXP", a.otp)
        assertEquals(listOf("PIN" to "1234"), a.extra)
        val kx = "\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\",\"TOTP\",\"Icon\",\"Last Modified\",\"Created\"\n" +
            "\"Root/Mail\",\"Gmail\",\"eve\",\"gpass\",\"https://mail.google.com\",\"\",\"otpauth://totp/G:eve?secret=JBSWY3DPEHPK3PXP\",\"0\",\"x\",\"y\"\n"
        val b = Importer.parse("keepassxc.csv", kx.toByteArray()).single()
        assertEquals("Root/Mail", b.folder)
        assertTrue(b.otp.startsWith("otpauth://"))
    }

    @Test fun semicolonCsv() {
        val it = Importer.parse("x.csv", "title;login;password\nVK;frank;vk1\n".toByteArray()).single()
        assertEquals("VK", it.title)
        assertEquals("frank", it.username)
    }

    @Test fun bitwardenJson() {
        val json = """{"encrypted":false,"folders":[{"id":"f1","name":"Банки"}],"items":[
          {"type":1,"name":"Сбер","folderId":"f1","notes":null,"login":{"username":"ivan","password":"s1","totp":"otpauth://totp/S?secret=JBSWY3DPEHPK3PXP","uris":[{"uri":"https://online.sberbank.ru"},{"uri":"https://sber.ru"}]},
           "fields":[{"name":"Кодовое слово","value":"кот","type":1}]},
          {"type":2,"name":"Заметка","notes":"текст","folderId":null},
          {"type":3,"name":"Карта","card":{"cardholderName":"IVAN","number":"4111","code":"123","expMonth":"1","expYear":"2030","brand":null}}]}"""
        val items = Importer.parse("bitwarden_export.json", json.toByteArray())
        assertEquals(3, items.size)
        assertEquals("Банки", items[0].folder)
        assertEquals("https://online.sberbank.ru", items[0].url)
        assertTrue(items[0].extra.contains("URL 2" to "https://sber.ru"))
        assertTrue(items[0].extra.contains("Кодовое слово" to "кот"))
        assertEquals("", items[1].password)
        assertEquals("текст", items[1].notes)
        assertTrue(items[2].extra.contains("CVV" to "123"))
    }

    @Test(expected = ImportException::class) fun encryptedBitwarden() {
        Importer.parse("b.json", """{"encrypted":true,"items":[]}""".toByteArray())
    }

    @Test fun onePassword1pux() {
        val data = """{"accounts":[{"vaults":[{"attrs":{"name":"Personal"},"items":[
          {"state":"active","overview":{"title":"Yandex","url":"https://passport.yandex.ru"},
           "details":{"loginFields":[{"designation":"username","value":"gleb"},{"designation":"password","value":"ya-pass"}],
             "notesPlain":"n","sections":[{"fields":[{"title":"one-time password","value":{"totp":"otpauth://totp/Y?secret=JBSWY3DPEHPK3PXP"}},
                                                    {"title":"PIN","value":{"concealed":"0000"}}]}]}},
          {"state":"archived","overview":{"title":"old"},"details":{}}]}]}]}"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("export.attributes")); z.write("{}".toByteArray()); z.putNextEntry(ZipEntry("export.data")); z.write(data.toByteArray()) }
        val bytes = bos.toByteArray()
        assertEquals(ImportFormat.ONEPASSWORD_1PUX, Importer.detect("x.1pux", bytes))
        val it = Importer.parse("x.1pux", bytes).single()
        assertEquals("gleb", it.username)
        assertEquals("ya-pass", it.password)
        assertTrue(it.otp.startsWith("otpauth://"))
        assertEquals(listOf("PIN" to "0000"), it.extra)
        assertEquals("Personal", it.folder)
    }

    @Test fun keepassDetected() {
        val kdbx = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(), 0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte())
        assertEquals(ImportFormat.KEEPASS, Importer.detect("db.kdbx", kdbx))
    }

    @Test fun otpUri() {
        assertEquals("otpauth://totp/Jira:dave?secret=JBSWY3DPEHPK3PXP&issuer=Jira", Importer.otpUri("jbsw y3dp ehpk 3pxp", "Jira", "dave"))
        assertNull(Importer.otpUri("not a secret!", "x", ""))
        assertEquals("otpauth://totp/A?secret=B", Importer.otpUri("otpauth://totp/A?secret=B", "x", ""))
    }
}
