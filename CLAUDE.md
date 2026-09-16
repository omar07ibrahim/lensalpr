# LensALPR — памятка для Claude Code

Приватное Android-приложение владельца (Kotlin, minSdk 31, targetSdk 36): телефон на заднем стекле
машины читает номера через физические линзы (CameraX + Camera2, YOLO26/ONNX, вендорский ALPR AAR),
ведёт базу встреч, определяет машины, которые едут следом (GPS, повороты), и тревожит голосом и через
Telegram-бота. Актуальный код — ветка `main`. Журнал изменений по датам — `HANDOFF.md` (свежее
сверху), полный перечень правок 16.09 — `docs/fixes-2026-09-16.md`, описание — `README.md`.

## Задача «установи на мой телефон»

1. **Нужно**: JDK 17, Android SDK (platform 36, build-tools 35), `adb`. На Windows это обычно Android
   Studio. Файл `local.properties` в репозитории указывает `sdk.dir=C:\Users\bokov\AppData\Local\Android\Sdk`;
   если SDK лежит в другом месте, поправь путь у себя (изменение не коммитить).
2. **Сборка**: Windows — `gradlew.bat :app:assembleDebug`; Linux/macOS — `bash ./gradlew :app:assembleDebug`.
   Результат: `app/build/outputs/apk/debug/app-debug.apk` (~190 МБ, модели внутри). Полная проверка:
   `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`.
3. **Ключ подписи** — самое важное. И debug, и release подписываются файлом `~/.android/debug.keystore`
   (см. `app/build.gradle`). Телефон принимает обновление поверх установленной версии только с тем же
   ключом. На основном компьютере владельца (Windows) ключ тот же, что у стоящей на телефоне сборки —
   `adb install -r` обновит приложение без потери базы. На другом компьютере ключ будет другим, и
   установка ответит `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Тогда либо скопировать
   `%USERPROFILE%\.android\debug.keystore` с основного компьютера, либо переустановить с сохранением данных:

   ```
   adb devices
   adb shell "run-as com.lensalpr.app tar cf /data/local/tmp/lensalpr-backup.tar databases shared_prefs files/evidence files/clips"
   adb pull /data/local/tmp/lensalpr-backup.tar .
   adb uninstall com.lensalpr.app
   adb install app\build\outputs\apk\debug\app-debug.apk
   adb push lensalpr-backup.tar /data/local/tmp/lensalpr-backup.tar
   adb shell "run-as com.lensalpr.app sh -c 'cd /data/data/com.lensalpr.app && tar xf /data/local/tmp/lensalpr-backup.tar'"
   adb shell rm /data/local/tmp/lensalpr-backup.tar
   ```

   Удалять приложение без резервной копии нельзя: в базе история встреч и списки. Перед `uninstall`
   убедись, что архив скачан и не пустой. Файл `shared_prefs/lensalpr_lock.xml` в архиве — пароль
   входа; если восстанавливать не нужно, владелец задаст новый при первом запуске.
4. **Установка**: `adb install -r app\build\outputs\apk\debug\app-debug.apk` (Linux: `app/build/...`).
5. **После установки** (только после переустановки, при обновлении поверх разрешения сохраняются):
   ```
   adb shell pm grant com.lensalpr.app android.permission.CAMERA
   adb shell pm grant com.lensalpr.app android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant com.lensalpr.app android.permission.ACCESS_COARSE_LOCATION
   adb shell pm grant com.lensalpr.app android.permission.POST_NOTIFICATIONS
   ```
   Первый запуск открывает экран пароля: 12 цифр, ввести дважды — это делает владелец руками.
   На экране настроек есть карточка «Разрешить показ поверх других приложений» — нужно выдать,
   иначе Android запрещает сканеру перезапускать себя без человека. Батарею — «без ограничений».
6. **Проверка**: `adb logcat -s LensALPR:* LensALPR.Scan:* LensALPR.Bot:*` при запуске; в Telegram
   боту `/check` (готовность) и `/status`.

## Чего не делать

- Не вводить три неверных пароля подряд: приложение стирает все свои данные (так задумано).
- Не слать `adb shell input …` (нажатия, ввод текста), пока телефон может быть в руках владельца:
  ввод попадёт в чужое окно. Скриншоты (`adb exec-out screencap -p`) и logcat — можно.
- Не пушить в `main` и не менять ветки без просьбы; ничего не публиковать (репозиторий приватный).
- Не переустанавливать приложение без резервной копии данных.

## Как владелец хочет, чтобы с ним работали

- По-русски, коротко, только реальные дефекты с `файл:строка`; догадки помечать как догадки.
- Замечания по безопасности (токен бота в коде, ключ подписи, разрешения) не нужны — это осознанно.
- Правки после 16.09 на дороге не проверялись: если спрашивает о состоянии, говорить прямо.
