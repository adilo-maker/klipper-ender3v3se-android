# Klipper Launcher (HOME-приложение)

Крошечное Android-приложение, которое заменяет домашний экран телефона и
запускает Klipper после загрузки.

## Зачем

HiOS (TECNO) не доставляет `BOOT_COMPLETED` приложению Termux:Boot, поэтому
скрипты из `~/.termux/boot/` после перезагрузки не выполняются. Лаунчер
назначен домашним экраном (`CATEGORY_HOME`) и стартует системой всегда.

HiOS также блокирует `startService` в приложение, которое ещё не запущено
(«Limit startService ... 3rd-to-died-3rd»). Поэтому лаунчер:

1. запускает активити Termux (под своей),
2. возвращает себя на передний план,
3. отправляет `RUN_COMMAND` в `com.termux.app.RunCommandService`.

Во время запуска лаунчер показывает заставку (принтер, «KLIPPER», спиннер,
«by: adilo321») отдельным системным окном `TYPE_APPLICATION_OVERLAY`.
Скрипт запускает Termux:X11 под этой заставкой, поэтому X-сервер сразу
получает правильное разрешение, а KlipperScreen успевает отрисоваться.
Когда Moonraker (7125), Mainsail (8080) и Fluidd (8081) отвечают, лаунчер
снимает заставку и выводит Termux:X11 на передний план. Тап по заставке
снимает её досрочно, таймаут — 150 секунд.

## Требования на телефоне

- Termux с `allow-external-apps = true` в `~/.termux/termux.properties`
- Разрешение `com.termux.permission.RUN_COMMAND` выдано лаунчеру
- `appops set com.klipper.launcher SYSTEM_ALERT_WINDOW allow`
- Termux:X11 в режиме **Direct touch** (`termux-x11-preference "touchMode:Direct touch"`);
  скрипт автозапуска выставляет это сам после старта X11
- Лаунчер назначен домашним:
  `cmd package set-home-activity com.klipper.launcher/.MainActivity`

## Сборка

Нужны JDK (javac/keytool), Android build-tools и platform (android.jar).

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Скрипт собирает `klipper-launcher.apk` через `aapt2` → `javac` → `d8` →
`zipalign` → `apksigner`. Промежуточные файлы кладутся в `C:\klipper-build\`,
потому что `aapt2` не понимает кириллицу в пути.

Ключ подписи: `C:\klipper-build\klipper.keystore` (alias `klipper`). Пароль
в `build.ps1` — демонстрационный (`change-me`); замените его на свой.
Не удаляйте keystore: с другим ключом обновление APK потребует полного
удаления приложения.

## Установка

```powershell
adb install -r klipper-launcher.apk
adb shell pm grant com.klipper.launcher com.termux.permission.RUN_COMMAND
adb shell appops set com.klipper.launcher SYSTEM_ALERT_WINDOW allow
adb shell cmd package set-home-activity com.klipper.launcher/.MainActivity
```

## Проверка

```powershell
adb shell am force-stop com.termux
adb shell input keyevent 3
# через ~60 секунд в фокусе должен быть com.termux.x11
adb shell dumpsys window | findstr mCurrentFocus
# проверка ввода: тап должен открыть меню/кнопку в KlipperScreen
adb shell input tap 1070 640
```

Если тап не реагирует, проверьте режим Termux:X11:
`termux-x11-preference list | findstr touchMode` — должно быть `Direct touch`.

Лог лаунчера: `adb logcat -s KlipperLauncher`.
