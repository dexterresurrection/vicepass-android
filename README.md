# Cloud Pass / Vice Pass для Android — исходный код

Менеджер паролей с кодами 2FA. Это **форк [KeePassDX](https://github.com/Kunzisoft/KeePassDX) 4.5.8** (© Kunzisoft / Jeremy Jamet и участники проекта),
изменённый для сервиса Cloud VPN. Лицензия — **GNU GPL v3** ([LICENSE](LICENSE), сторонние — [LICENSES](LICENSES)), без каких-либо гарантий.

Здесь исходники ровно той версии, что указана тегом: `v0.1.2` — это `CloudPass-0.1.2.apk` и `VicePass-0.1.2.apk`
(пакет `uk.zjd.cloudpass`, versionCode 3). Приложение не связано с авторами KeePassDX и не одобрено ими;
об ошибках этого приложения не пишите в KeePassDX. Исходный README проекта — [README-KeePassDX.md](README-KeePassDX.md).

*English: source code of Cloud Pass / Vice Pass for Android, a modified version of KeePassDX 4.5.8, GNU GPL v3. Not affiliated with KeePassDX. The tag matches the published APK.*

## Что изменено

Изменения внесены с 20 по 24 ноября 2026 года. Подробно — в [NOTICE.md](NOTICE.md).

## Сборка

JDK 17, Android SDK 36, NDK 25.2.9519653, CMake 3.22.1:

```
./gradlew :app:assembleLibreRelease
```

Получится неподписанный `app/build/outputs/apk/libre/release/app-libre-release-unsigned.apk`; подпишите его своим ключом
(`zipalign -P 16 4` и `apksigner`). Ключ, которым подписаны наши сборки, не публикуется, поэтому своя сборка не встанет
поверх нашей — сначала сохраните хранилище.

Vice Pass — тот же APK с тремя отличиями:
1. `assets/brand.json`: `{"v":1,"brand":"vice","name":"Vice Pass","theme":"vice-night"}`;
2. в манифесте `uk.zjd.cloudpass.ViceLauncher` включён (`android:enabled="true"`), `uk.zjd.cloudpass.CloudLauncher` выключен;
3. у `<application>` имя `@string/app_name_vice` и значок `@mipmap/ic_launcher_vice`.

Не включены папки `art` и `fastlane` исходного проекта (к сборке не относятся), шаблоны обращений KeePassDX и скрипт подписи.
`autofilltest` — вспомогательное приложение для проверки автозаполнения, в APK не входит.

## Хранилище

Хранилище — обычный файл KeePass (KDBX 4), зашифрованный мастер-паролем на устройстве. Мастер-пароль на сервер не передаётся;
сервер синхронизации хранит только зашифрованный файл и его прошлые версии.
