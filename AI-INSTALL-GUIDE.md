# Klipper на Android без root — полное руководство для ИИ и людей

**Версия:** 2026-11-07  
**Проверено на:** TECNO BF6 (POP 7), Android 12 / API 31, HiOS, экран 720×1612 (landscape 1612×720), 2 ГБ ОЗУ (`MemTotal` ≈ 1.9 ГБ)  
**Принтер:** Ender 3 V3 SE, плата Creality 4.2.2 / GD32F303 (сборка Klipper как STM32F103xe)  
**Подход:** нативный Termux, **без root и без proot**. Klipper, Moonraker, Mainsail, Fluidd, KlipperScreen, nginx и USB-мост работают внутри Android-приложения Termux. Android остаётся хостом, а принтер подключается через USB-C OTG и приложение-мост.

> Это инженерный playbook, а не официальная документация. Klipper управляет нагревателями и моторами; ошибка в конфиге или прошивке может испортить принтер или создать пожарную опасность. Не оставляйте печать без присмотра, пока не проверены термисторы, thermal runaway и концевые выключатели. Все действия — на ваш риск.

---

## 0. Как пользоваться этим файлом

### 0.1. Если вы человек

Передайте этот файл ИИ-агенту вместе с доступом к компьютеру и телефону по ADB. Лучше работать не «одним промптом», а по этапам: ИИ должен после каждого этапа показывать команды, вывод проверки и ждать подтверждения, особенно перед прошивкой MCU и удалением приложений.

### 0.2. Если вы ИИ

Ты — инженер, который разворачивает Klipper-хост на старом Android-телефоне. Твои правила:

1. **Прочитай файл целиком** до первой команды.
2. **Спроси пользователя** то, чего нет в файле: точная модель телефона, версия Android/HiOS, модель принтера, ревизия платы, есть ли microSD FAT32, USB-C OTG, заряд батареи, готов ли он подтверждать USB-доступ на телефоне.
3. **Не выполняй destructive-команды без подтверждения:** `pm uninstall`, `pm clear`, `rm -rf`, прошивка MCU, смена HOME-приложения, удаление keystore.
4. **Работай этапами.** После каждого этапа проверяй результат командами и скриншотом. Если проверка не прошла — остановись и диагностируй, не иди дальше.
5. **Не выдумывай пути и имена пакетов.** В Termux всё находится в `/data/data/com.termux/files/...`; это не обычный Linux.
6. **Не используй `/system/bin/am`** для запуска активити из-под UID Termux на Android 12+. Используй `$PREFIX/bin/am`.
7. **Не пытайся подключить принтер как `/dev/ttyACM0`.** На Android без root нет доступа к USB-serial ядру; используется PTY-мост.
8. **Не обновляй Klipper на хосте, не пересобрав прошивку MCU** из того же коммита. Иначе возможен рассинхрон протокола.
9. **Не публикуй** реальный IP, серийник телефона, токен `bridge.conf`, пароль SSH и keystore в открытом виде.
10. **Проверяй заряд** перед перезагрузкой: при <30% телефон может выключиться в середине boot-последовательности.

### 0.3. Готовый промпт для другого ИИ

```text
Ты — инженер по Klipper, Android и Termux. Прочитай AI-INSTALL-GUIDE.md целиком.
Сначала задай мне вопросы: модель телефона, Android/HiOS, модель принтера, ревизия платы MCU,
есть ли microSD FAT32 и USB-C OTG, заряд батареи, готов ли я подтверждать USB-доступ.
Дальше веди меня по этапам 0–15 из файла. После каждого этапа показывай команды и вывод проверки.
Не прошивай MCU, пока не подтверждена ревизия платы. Не удаляй приложения и данные без моего согласия.
Не используй /system/bin/am — только $PREFIX/bin/am. Не подключай принтер как /dev/ttyACM0:
на Android используется PTY-мост klipper-android-bridge.
Если что-то не работает — остановись, собери логи и предложи 2–3 гипотезы, а не продолжай вслепую.
```

### 0.4. Соглашения

| Обозначение | Значение |
|---|---|
| `PHONE_IP` | IP телефона в Wi-Fi, например `192.168.0.109` |
| `SERIAL` | серийник для ADB из `adb devices -l` |
| `$HOME` | `/data/data/com.termux/files/home` |
| `$PREFIX` | `/data/data/com.termux/files/usr` |
| `~/...` | путь внутри Termux-home, не Windows/Linux-хоста |
| `<TOKEN>` | токен USB-моста, генерируется приложением/установщиком |
| `SD` | FAT32 microSD для прошивки принтера |

Команды в статье — для Windows PowerShell + ADB. На Linux/macOS синтаксис ADB тот же, а PowerShell-обёртки (`$b64`, `Start-Sleep`) замените на bash-эквиваленты.

---

## 1. Что должно получиться

В результате телефон:

- загружается в **Klipper Launcher** вместо штатного рабочего стола;
- показывает брендированную заставку, пока не поднимутся Moonraker и веб-интерфейсы;
- автоматически запускает Klipper, Moonraker, nginx, USB-мост и KlipperScreen;
- показывает KlipperScreen на весь экран в landscape, с русским языком и без засыпания;
- принимает касания пальцем (Direct touch), а не двигает невидимый курсор (Trackpad);
- отдаёт Mainsail на `http://PHONE_IP:8080`, Fluidd — на `:8081`, Moonraker API — на `:7125`;
- подключается к принтеру по USB-C OTG через приложение **Klipper For Android** и PTY-мост;
- после прошивки MCU показывает `Ready` и позволяет печатать из Mainsail/Fluidd/KlipperScreen.

### 1.1. Проверенные версии

| Компонент | Версия / коммит | Откуда |
|---|---|---|
| Termux | 0.118.3 | F-Droid или GitHub Termux |
| Termux:X11 | 1.03.01-f1d9834 (APK), пакет `termux-x11-nightly 1.03.01-6` | GitHub Termux:X11 |
| Klipper For Android (bridge) | 0.1.0-test.1, пакет `dev.klipper.androidbridge` | `thingsapart/klipper_termux` Releases |
| Klipper-хост | `60fc7aa` (2026-08-18) | `Klipper3d/klipper` |
| Moonraker | `d5ee171` (2026-06-29) | `Arksine/moonraker` |
| Mainsail | v2.18.2 | `mainsail-crew/mainsail` |
| Fluidd | v1.37.7 | `fluidd-core/fluidd` |
| KlipperScreen | `34610ce` (2026-10-25) | `KlipperScreen/KlipperScreen` |
| Python | 3.14.8 | Termux |
| nginx | 1.31.8 | Termux |
| Klipper Launcher | 1.0, `com.klipper.launcher` | этот проект |
| Прошивка Ender 3 V3 SE | 40 160 байт, SHA256 `FD8C61A21F06A87C4BA077FF6DA44FE584F6E19349218B3EE61E7E6A57D25CA3` | сборка из `60fc7aa` |

### 1.2. Официальная база и наши доработки

Базовая установка Klipper/Moonraker/Mainsail/USB-моста — проект **`thingsapart/klipper_termux`** (в коде встречается имя `klipper-android`, `K4A`, `klctl`). Он ставит нативный Termux-стек, патчит Klipper под Android и создаёт runit-сервисы.

Наши доработки, которых нет в базовом установщике:

- Fluidd на порту 8081;
- KlipperScreen на Termux:X11 с патчами под Android;
- HOME-лаунчер с заставкой и автозапуском (обход блокировки Termux:Boot в HiOS);
- boot-скрипт с PID-lock, `sv start`, порядком запуска X11 и Direct touch;
- watchdog KlipperScreen;
- русский язык, отключение blanking и скрытие нерабочей панели «Сеть»;
- прошивка Ender 3 V3 SE из точного коммита хоста.

---

## 2. Архитектура

```text
┌─────────────────────────────────────────────────────────────────────┐
│ Android 12 / HiOS                                                   │
│                                                                     │
│  Klipper Launcher (HOME)                                            │
│   ├─ splash overlay TYPE_APPLICATION_OVERLAY (1612×720)             │
│   ├─ startActivities(Termux activity, self)                         │
│   ├─ RUN_COMMAND → ~/.termux/boot/start-klipper.sh splash ...       │
│   └─ poll 127.0.0.1:7125 / 8080 / 8081 → убрать splash → X11        │
│                                                                     │
│  Termux                                                             │
│   ├─ runit: klipper, moonraker, klipper-web, klipper-android-bridge │
│   ├─ nginx :8080 Mainsail, :8081 Fluidd                             │
│   ├─ termux-x11 :0 -ac  ←→  Termux:X11 activity                     │
│   ├─ ks_watchdog.sh → KlipperScreen (GTK3)                          │
│   └─ Klipper ← PTY ← native bridge ← TCP ← Android USB service      │
│                                                                     │
│  Klipper For Android (dev.klipper.androidbridge)                    │
│   └─ USB host API → USB-C OTG → плата принтера                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.1. Почему именно так

- **Termux, а не proot.** Нужен прямой доступ к Android USB host API и нормальная работа сервисов; proot добавляет слой и ломает USB/charging. Официальный установщик `klipper_termux` принципиально не поддерживает proot.
- **USB-мост, а не `/dev/ttyACM0`.** Android без root не отдаёт USB-serial ядро приложению. Приложение `Klipper For Android` открывает USB-устройство через `UsbManager`, поднимает loopback TCP и отдаёт Termux PTY `/data/data/com.termux/files/usr/var/run/klipper-android/main`. Klipper думает, что это обычный serial.
- **PTY, а не сокет Klipper.** Klipper не умеет напрямую TCP-serial; PTY — самый совместимый вариант. В `printer.cfg` обязателен `restart_method: command`, потому что PTY не передаёт DTR/RTS-сброс.
- **Launcher, а не Termux:Boot.** HiOS не доставляет `BOOT_COMPLETED` в Termux:Boot. HOME-активити система запускает всегда; она же обходит запрет HiOS на `startService` в ещё не запущенное приложение.
- **Overlay, а не вторая activity.** `TYPE_APPLICATION_OVERLAY` с `FLAG_NOT_FOCUSABLE` позволяет держать заставку поверх Termux:X11, не забирая у него фокус и не показывая系统ные панели. Если делать splash отдельной activity, X11 не получает правильный размер и фокус.

---

## 3. Что нужно подготовить

### 3.1. Железо

- Старый Android-телефон с OTG. Проверено: TECNO BF6 (POP 7), Android 12.
- Зарядка/кабель питания. Для финальных тестов — charger, а не USB-порт ПК: при 12% телефон может умереть в boot.
- USB-C OTG-кабель (data, не charge-only).
- При желании — powered USB hub: плата принтера может потреблять больше, чем отдаёт телефон.
- microSD **FAT32** для прошивки принтера.
- Принтер с исправной платой и загрузчиком. Для Ender 3 V3 SE — Creality 4.2.2 / GD32F303.
- Компьютер с ADB (Windows/Linux/macOS) — для установки APK, скриптов и диагностики.

### 3.2. Софт

- Termux **с F-Droid или официального GitHub**, не из Play Store. Все Termux-приложения/плагины должны быть из одного источника: подписи разные, обновление поверх чужой сборки невозможно.
- Termux:X11 APK.
- Klipper For Android APK (`thingsapart/klipper_termux` Releases).
- Klipper Launcher APK (этот проект).
- Android SDK platform-tools (ADB).
- JDK + Android build-tools/platform — только если будете пересобирать launcher.
- ARM GNU toolchain — если будете собирать прошивку MCU на ПК.

### 3.3. Сеть и доступ

- Телефон в Wi-Fi; желательно статический IP или DHCP reservation на роутере.
- ПК и телефон в одной сети, если нужен ADB по TCP/IP.
- Не открывайте порты `7125`, `8080`, `8081` в интернет. Moonraker доверяет локальным подсетям; это не публичный сервис.

---

## 4. Карта путей и портов

### 4.1. Termux

| Путь | Назначение |
|---|---|
| `$HOME` | `/data/data/com.termux/files/home` |
| `$PREFIX` | `/data/data/com.termux/files/usr` |
| `~/klipper` | исходники Klipper |
| `~/klippy-env` | venv Klipper |
| `~/moonraker` | исходники Moonraker |
| `~/moonraker-env` | venv Moonraker |
| `~/mainsail` | статика Mainsail |
| `~/fluidd` | статика Fluidd |
| `~/KlipperScreen` | исходники KlipperScreen |
| `~/.KlipperScreen-env` | venv KlipperScreen (с `--system-site-packages`) |
| `~/printer_data/config` | printer.cfg, moonraker.conf, nginx.conf, KlipperScreen.conf, bridge.conf |
| `~/printer_data/logs` | klippy.log, moonraker.log, nginx-*.log |
| `~/printer_data/comms` | UNIX-сокет Klipper |
| `~/printer_data/gcodes` | G-code и virtual SD |
| `~/.termux/boot/start-klipper.sh` | boot-скрипт, вызывается лаунчером |
| `~/ks_watchdog.sh` | watchdog KlipperScreen/X11 |
| `~/start_klipperscreen.sh` | ручной запуск UI |
| `~/boot.log` | лог boot-скрипта |
| `~/ks_run.log` | stdout/stderr KlipperScreen |
| `~/ks_watchdog.log` | лог watchdog |
| `~/x11.log` | лог Termux:X11 |
| `$PREFIX/var/service/` | runit-сервисы |
| `$PREFIX/var/run/klipper-android/main` | PTY для Klipper |
| `$PREFIX/bin/am` | Termux-обёртка `am` (не `/system/bin/am`!) |
| `$PREFIX/bin/termux-x11-preference` | настройки Termux:X11 |
| `~/.local/state/klipper-android/installer.log` | лог официального установщика |

### 4.2. Порты

| Порт | Сервис |
|---|---|
| `7125` | Moonraker API/WebSocket |
| `8080` | Mainsail (nginx) |
| `8081` | Fluidd (nginx) |
| `27831` | loopback TCP USB-моста (только localhost) |
| `2020` | опциональный SSH Termux (если включали) |

### 4.3. Android

| Компонент | Пакет / activity |
|---|---|
| Termux | `com.termux` / `com.termux.app.TermuxActivity` |
| Termux:X11 | `com.termux.x11` / `com.termux.x11.MainActivity` |
| Klipper For Android | `dev.klipper.androidbridge` / `dev.klipper.androidbridge.MainActivity` |
| Klipper Launcher | `com.klipper.launcher` / `com.klipper.launcher.MainActivity` |
| RUN_COMMAND service | `com.termux/.app.RunCommandService`, action `com.termux.RUN_COMMAND` |

---

## 5. Этап 0 — разведка телефона

Подключите телефон, включите «Для разработчиков» и «Отладка по USB», разрешите отпечаток ПК.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb shell "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.product.device"
& $adb shell "getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.display.id"
& $adb shell "wm size; wm density"
& $adb shell "dumpsys battery | grep -E 'level|status|powered'"
& $adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
```

Запишите:

- модель и Android;
- физическое разрешение и density;
- текущий HOME (штатный лаунчер);
- заряд;
- серийник для `-s SERIAL`.

Проверка OTG:

```powershell
& $adb shell "dumpsys usb | head -60"
```

Если телефон не виден:

```powershell
& $adb kill-server
& $adb start-server
& $adb devices -l
```

На Android 12 + HiOS ADB по USB обычно работает без root. Для ADB по Wi-Fi:

```powershell
& $adb tcpip 5555
& $adb connect PHONE_IP:5555
& $adb devices -l
```

После setup отключите `adb tcpip`/отладку, если не нужна.

---

## 6. Этап 1 — подготовка Android и Termux

### 6.1. Установка приложений

1. Termux 0.118.3 — F-Droid/GitHub.
2. Termux:X11 — GitHub Releases.
3. Klipper For Android — Releases `thingsapart/klipper_termux`.
4. Klipper Launcher — `klipper-launcher.apk` из этого проекта.

Не ставьте Termux из Play Store вместе с F-Droid-версией: подписи разные, плагины несовместимы.

### 6.2. Первый запуск Termux

```bash
pkg update -y && pkg upgrade -y
termux-setup-storage
termux-wake-lock
```

`termux-setup-storage` запросит доступ к файлам — разрешите, иначе не сможете экспортировать прошивку на SD.

### 6.3. Разрешить внешние команды

Это нужно, чтобы лаунчер мог отправлять `RUN_COMMAND` в Termux.

```bash
mkdir -p ~/.termux
grep -q '^allow-external-apps' ~/.termux/termux.properties \
  && sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties \
  || echo 'allow-external-apps = true' >> ~/.termux/termux.properties
termux-reload-settings
```

Проверка:

```bash
tail -3 ~/.termux/termux.properties
```

Должно быть `allow-external-apps = true`.

### 6.4. Настройки Android/HiOS

Обязательно:

- **Батарея:** снять оптимизацию для Termux, Termux:X11, Klipper For Android, Klipper Launcher. В HiOS — «Разрешить автозапуск» / «Автозапуск приложений».
- **Экран:** не гасить, пока подключён charger. Через ADB:

```powershell
& $adb shell "settings put system screen_off_timeout 2147483647"
& $adb shell "settings put global stay_on_while_plugged_in 7"
& $adb shell "svc power stayon true"
```

- **Ориентация:** лаунчер и Termux:X11 принудительно landscape; автоповорот можно не трогать.
- **Дата/время:** автоматически, иначе TLS/apt могут ругаться.
- **HiOS:** «Заблокировать в недавних» для Termux и bridge; иначе система выгрузит мост.
- **Разрешение на наложение:** для Klipper Launcher (`SYSTEM_ALERT_WINDOW`), иначе заставка будет только внутри activity и может мигать.
- **USB:** при первом подключении принтера появится диалог «Разрешить доступ к USB-устройству?» — разрешите и запомните.

Если HiOS убивает Termux после выключения экрана — проверьте, что снята оптимизация и включён автозапуск. В критичных случаях держите телефон на зарядке и в режиме «Не выключать экран».

---

## 7. Этап 2 — debloat (опционально, обратимо)

Удаление через `pm uninstall --user 0` обратимо: пакет остаётся в системе, но скрыт для пользователя. Вернуть:

```powershell
& $adb shell "pm install-existing <package>"
```

Список, который был удалён в этом проекте (23 пакета):

```text
android.autoinstalls.config.transsion.device
com.google.android.apps.tachyon
com.google.android.videos
com.transsion.autotest.factory
com.transsion.batterylab
com.transsion.carlcare
com.transsion.childmode
com.transsion.fmradio
com.transsion.hilauncher.upgrade
com.transsion.kolun.assistant
com.transsion.letswitch
com.transsion.magicshow
com.transsion.microintelligence
com.transsion.phonemaster
com.transsion.plat.appupdate
com.transsion.powercenter
com.transsion.repaircard
com.transsion.statisticalsales
com.transsion.teop
com.transsion.trancare
com.transsion.tranengine
com.transsnet.store
tech.palm.id
```

Массовое удаление:

```powershell
$packages = @(
  "android.autoinstalls.config.transsion.device",
  "com.google.android.apps.tachyon",
  "com.google.android.videos",
  "com.transsion.autotest.factory",
  "com.transsion.batterylab",
  "com.transsion.carlcare",
  "com.transsion.childmode",
  "com.transsion.fmradio",
  "com.transsion.hilauncher.upgrade",
  "com.transsion.kolun.assistant",
  "com.transsion.letswitch",
  "com.transsion.magicshow",
  "com.transsion.microintelligence",
  "com.transsion.phonemaster",
  "com.transsion.plat.appupdate",
  "com.transsion.powercenter",
  "com.transsion.repaircard",
  "com.transsion.statisticalsales",
  "com.transsion.teop",
  "com.transsion.trancare",
  "com.transsion.tranengine",
  "com.transsnet.store",
  "tech.palm.id"
)
foreach ($p in $packages) { & $adb shell "pm uninstall --user 0 $p" }
```

**Не удаляйте** основной лаунчер `com.transsion.hilauncher`, SystemUI, телефон, настройки, Termux, bridge, пока не убедились, что Klipper Launcher назначен HOME и работает. Лучше делать debloat после этапа 11 (launcher) и перед финальной перезагрузкой.

Проверка, что телефон жив и HOME на месте:

```powershell
& $adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
& $adb shell "input keyevent 3"
```

Если после debloat телефон не загружается в лаунчер — верните пакеты через `pm install-existing` или сделайте `adb shell cmd package set-home-activity com.transsion.hilauncher/...` (уточните activity у пользователя).

---

## 8. Этап 3 — базовый Klipper-хост

### 8.1. Официальный установщик

Это самый надёжный путь: он ставит зависимости, Klipper, Moonraker, Mainsail, nginx, USB-мост, runit-сервисы и патчит Klipper под Android.

```bash
pkg install -y git
cd ~
git clone https://github.com/thingsapart/klipper_termux.git
cd klipper_termux
installer/install.sh --source-dir "$PWD"
```

Если установщик спрашивает режим при существующей установке:

- **UPDATE** — обновить, сохранив `printer_data`, конфиги, G-code и логи;
- **DELETE** — удалить managed-файлы; **не выбирайте**, если не хотите потерять конфиги;
- `--non-interactive` без режима на существующей установке откажется работать — это защита.

Установщик:

- ставит `git python clang make ndk-sysroot libffi openssl zlib curl unzip iproute2 termux-services nginx`;
- создаёт `~/klippy-env`, `~/moonraker-env`;
- клонирует Klipper/Moonraker (shallow);
- собирает нативный C-мост;
- создаёт `bridge.conf`, `moonraker.conf`, `nginx.conf`, сервисы;
- добавляет `[machine] provider: none` в Moonraker (на Android нет systemd/DBus);
- патчит Klipper RAW clock (см. 8.4);
- добавляет `allow-external-apps = true`;
- ставит команды `klctl`, `klipper-android-runner`, `klipper-android-bridge`.

Проверка:

```bash
klctl doctor
klipper-android-runner start
sv status klipper moonraker klipper-web klipper-android-bridge
curl -s -o /dev/null -w "moonraker %{http_code}\n" http://127.0.0.1:7125/
curl -s -o /dev/null -w "mainsail %{http_code}\n" http://127.0.0.1:8080/
```

Ожидаемо: Moonraker отвечает, Mainsail отдаёт HTML, Klipper может быть в `startup`/`Unable to connect`, пока нет принтера.

### 8.2. Если установщик недоступен

Ручной путь возможен, но он воспроизводит много ошибок:

1. Поставить пакеты Termux.
2. Создать venv для Klipper и Moonraker.
3. Клонировать Klipper/Moonraker на нужные коммиты.
4. Установить Python-зависимости из `requirements.txt`.
5. Собрать Klipper C-helper.
6. Применить Android-патч RAW clock.
7. Собрать C-мост из `bridge/build-termux.sh`.
8. Написать runit-сервисы и `bridge.conf`.

Если пользователь просит ручной путь, ИИ должен сначала попробовать официальный установщик и отступать только при объективной причине.

### 8.3. Moonraker

`~/printer_data/config/moonraker.conf`:

```ini
[server]
host: 0.0.0.0
port: 7125
klippy_uds_address: /data/data/com.termux/files/home/printer_data/comms/klippy.sock

[file_manager]
check_klipper_config_path: True
enable_config_write_access: True

# Android/Termux: нет systemd и system D-Bus.
[machine]
provider: none
validate_service: False
validate_config: False

[authorization]
trusted_clients:
    127.0.0.0/8
    10.0.0.0/8
    172.16.0.0/12
    192.168.0.0/16

[zeroconf]
mdns_hostname: klipper-android
```

Важно:

- `provider: none` обязателен. Без него Moonraker пытается systemd/DBus и падает/сыпет ошибками.
- `trusted_clients` — только локальные подсети. Не добавляйте `0.0.0.0/0`.
- `klippy_uds_address` должен указывать на сокет Klipper, а не на TCP.

### 8.4. Патч Klipper RAW clock

Некоторые старые Android-ядра возвращают некорректный `CLOCK_MONOTONIC_RAW`. Klipper использует его для host timestamps; получается «Timer too close» при чистых данных. Официальный установщик подменяет RAW на `CLOCK_MONOTONIC` в `chelper/pyhelper.c` и пересобирает `c_helper.so`.

Если ставите Klipper вручную — этот патч обязателен. Не меняйте протокол, планировщик или motion-код; правка касается только источника времени.

### 8.5. nginx и Mainsail

Установщик создаёт `~/printer_data/config/nginx.conf` и сервис `klipper-web`. Mainsail — `~/mainsail`, порт 8080. nginx запускается как foreground-процесс под runit:

```bash
nginx -c ~/printer_data/config/nginx.conf -p ~/printer_data
```

Системный сервис `nginx` из Termux при этом может быть `down` — это нормально, его не нужно включать.

### 8.6. Fluidd (дополнительно)

Скачайте `fluidd.zip` с GitHub Releases Fluidd и распакуйте в `~/fluidd`. Затем добавьте в `nginx.conf` второй `server` на 8081 (полный конфиг — в приложении C). В нём должны быть:

- `root /data/data/com.termux/files/home/fluidd;`
- `try_files $uri $uri/ /index.html;`
- proxy `/websocket` на `127.0.0.1:7125` с `Upgrade`/`Connection`;
- proxy `/printer`, `/api`, `/access`, `/machine`, `/server` на Moonraker;
- `proxy_set_header Host $http_host` — иначе Moonraker может отвергнуть WebSocket same-origin.

Перезапуск:

```bash
sv restart klipper-web
curl -s -o /dev/null -w "fluidd %{http_code}\n" http://127.0.0.1:8081/
```

### 8.7. Проверка базового стека

```bash
sv status klipper moonraker klipper-web klipper-android-bridge
ss -ltnp 2>/dev/null | grep -E ':(7125|8080|8081)\b'
curl -s http://127.0.0.1:7125/printer/info | head -c 300; echo
```

Если `klipper` в цикле `waiting for bridge PTY` — это ожидаемо без принтера/моста. Moonraker и веб-интерфейсы должны работать независимо.

---

## 9. Этап 4 — Termux:X11 и KlipperScreen

### 9.1. Termux:X11

Установите APK Termux:X11 (версия должна соответствовать пакету `termux-x11-nightly`). Затем:

```bash
pkg install -y x11-repo
pkg install -y termux-x11-nightly
```

Запуск X-сервера и activity:

```bash
termux-x11 :0 -ac > ~/x11.log 2>&1 &
sleep 2
am start -n com.termux.x11/.MainActivity
```

`am` — это `$PREFIX/bin/am` (Termux-обёртка). Не заменяйте на `/system/bin/am`.

### 9.2. Настройки Termux:X11 (критично)

```bash
termux-x11-preference \
  "touchMode:Direct touch" \
  "hideCutout:true" \
  "forceOrientation:landscape" \
  "fullscreen:true" \
  "displayResolutionMode:exact" \
  "adjustResolution:false" \
  "pointerCapture:false"
termux-x11-preference list | grep -Ei "touchMode|hideCutout|forceOrientation|fullscreen"
```

Почему каждое:

- `touchMode:Direct touch` — иначе Termux:X11 работает как тачпад: палец двигает невидимый курсор, KlipperScreen «не реагирует». Это главная причина «сенсор не работает».
- `hideCutout:true` — спрятать вырез камеры.
- `forceOrientation:landscape` — телефон всегда горизонтально.
- `fullscreen:true` — убрать системные панели.
- `displayResolutionMode:exact` + `adjustResolution:false` — X11 получает ровно нужный размер, без пересчёта.
- `pointerCapture:false` — не перехватывать курсор.

На TECNO BF6 физический экран 1612×720, а X11 показывает 1608×720 из-за навигационной полосы. Это нормально; launcher-заставка использует реальные 1612×720 через `getRealMetrics`.

### 9.3. Зависимости KlipperScreen

```bash
pkg install -y python python-pip pygobject gtk3 gdk-pixbuf pango gobject-introspection python-psutil
git clone https://github.com/KlipperScreen/KlipperScreen.git ~/KlipperScreen
cd ~/KlipperScreen
git checkout 34610ce
python -m venv --system-site-packages ~/.KlipperScreen-env
~/.KlipperScreen-env/bin/pip install --upgrade pip
~/.KlipperScreen-env/bin/pip install pycairo python-mpv requests websocket-client Jinja2
```

`--system-site-packages` важен: Termux-пакет `pygobject` и GTK3 должны быть видны venv. Если ставить PyGObject из pip в чистый venv, он не соберётся без системных заголовков.

Проверка импортов:

```bash
~/.KlipperScreen-env/bin/python -c "import gi; gi.require_version('Gtk','3.0'); from gi.repository import Gtk; print('GTK OK')"
```

### 9.4. Патчи KlipperScreen под Termux

Полный скрипт — в приложении H. Он делает пять правок:

1. **Spoolman:** динамический SVG-градиент роняет librsvg на Termux → статичная иконка `spool`.
2. **Батарея:** `psutil.sensors_battery()` не имеет доступа на Android → скрыть виджет, не падать.
3. **CPU/RAM:** Moonraker на Android не отдаёт `system_cpu_usage`/`system_memory` → безопасный `.get()` и дефолты.
4. **DPMS:** нет `libXext`/`xset` → stub `get_DPMS_state`, чтобы KlipperScreen не падал.
5. **xset:** `FileNotFoundError` при `xset s off` → ловить и продолжать.

Запуск:

```bash
~/.KlipperScreen-env/bin/python ~/patch_ks.py
```

Скрипт идемпотентный: повторный запуск не дублирует правки.

### 9.5. Ручной запуск UI

```bash
export DISPLAY=:0
export XDG_RUNTIME_DIR=$PREFIX/var/run
cd ~/KlipperScreen
~/.KlipperScreen-env/bin/python -X faulthandler ~/KlipperScreen/screen.py
```

Или готовый скрипт:

```bash
bash ~/start_klipperscreen.sh
```

Проверка:

```bash
pgrep -af "KlipperScreen/screen.py"
tail -30 ~/ks_run.log
adb shell "dumpsys window | grep mCurrentFocus"
```

В фокусе должен быть `com.termux.x11/com.termux.x11.MainActivity`, а внутри X11 — окно KlipperScreen.

### 9.6. Конфиг KlipperScreen

`~/printer_data/config/KlipperScreen.conf`:

```ini
[main]
language: ru
screen_blanking: off

[printer default]
moonraker_host: 127.0.0.1
moonraker_port: 7125
[menu __splashscreen network]
enable: False

[menu __main more network]
enable: False

#~# --- Do not edit below this line. This section is auto generated --- #~#
#~#
#~# [main]
#~# screen_blanking = off
#~# screen_blanking_printing = off
#~# font_size = large
#~# confirm_estop = True
#~# use_dpms = False
#~# show_heater_power = True
#~# language = ru
#~#
```

Критичные нюансы:

- **Язык — `ru`, а не `Русский (ru)`.** KlipperScreen сохраняет в auto-generated секции display name, и тогда перевод может откатиться на английский. В обеих секциях должен быть код `ru`.
- **`screen_blanking = off`** должен быть и в пользовательской, и в auto-generated секции. Иначе KlipperScreen через 60 секунд включает screensaver/DPMS, и экран выглядит чёрным, хотя процесс жив.
- **Автосекция `#~#` перекрывает пользовательскую.** Если в ней остались `screen_blanking = 60` или `language = Русский (ru)` — исправьте обе.
- **Панель «Сеть» скрыта.** Она импортирует `sdbus_block.networkmanager`, которого нет в Termux; открытие даёт `No module named 'sdbus_block'`. Wi-Fi управляется Android. Скрываем оба пункта: `__splashscreen network` и `__main more network`.
- **`use_dpms = False`** уже в автосекции; при патче DPMS это не критично, но оставьте.

После правок перезапустить:

```bash
pkill -f "KlipperScreen/screen.py"
# watchdog поднимет заново; или запустить вручную
tail -20 ~/ks_run.log | grep -E "Selected lang|Blanking timeout"
```

Ожидаемо:

```text
Selected lang: ru OS lang: C
Blanking timeout: off
```

### 9.7. Проверка языка, blanking и тача

Скриншот:

```powershell
& $adb shell screencap -p /sdcard/ks.png
& $adb pull /sdcard/ks.png .\ks.png
```

Не используйте `adb exec-out screencap -p > file` в PowerShell: перенаправление может испортить бинарный PNG. Только `screencap` в файл на телефоне + `adb pull`.

Проверка blanking: оставить экран на 90+ секунд, снять скриншот. В логе не должно быть `Showing Screensaver`.

Проверка тача:

```powershell
& $adb shell input tap 1070 620
```

Должно открыться меню. Это проверяет X11/координаты, но **не заменяет проверку пальцем**. Попросите пользователя коснуться экрана и подтвердить.

---

## 10. Этап 5 — boot-скрипт и watchdog

### 10.1. Файлы

- `~/.termux/boot/start-klipper.sh` — главный boot-скрипт.
- `~/ks_watchdog.sh` — watchdog.
- `~/start_klipperscreen.sh` — ручной запуск.

Скопируйте их из проекта (или из приложений E–G), выставьте executable:

```bash
mkdir -p ~/.termux/boot
chmod +x ~/.termux/boot/start-klipper.sh ~/ks_watchdog.sh ~/start_klipperscreen.sh
```

### 10.2. Что делает boot-скрипт

1. Экспортирует Termux-переменные (`HOME`, `PREFIX`, `PATH`, `LD_LIBRARY_PATH`, `TMPDIR`, `SVDIR`).
2. Пишет лог в `~/boot.log`.
3. Берёт PID-lock `~/.klipper-start.pid`; если другой экземпляр жив — выходит. Это защита от двойного HOME.
4. `termux-wake-lock`.
5. Если режим `splash` и стек уже работает — только переприменяет X11 prefs и проверяет watchdog, затем выходит (быстрый путь для HOME во время печати).
6. `service-daemon start` + `sv start klipper moonraker klipper-web klipper-android-bridge`. Важно: `service-daemon start` сам по себе не поднимает сервисы с `down`-файлами; `sv start` обязателен.
7. В `splash`-режиме:
   - если лаунчер передал `bridge`, запускает `dev.klipper.androidbridge/.MainActivity` и ждёт PTY;
   - запускает `termux-x11 :0 -ac` (бинарник) **до** activity, иначе activity покажет «Not connected»;
   - запускает `com.termux.x11/.MainActivity`;
   - применяет `fix_x11_prefs` (Direct touch и остальное);
   - повторно запускает activity, чтобы применить разрешение;
   - запускает watchdog с `KS_WATCHDOG_SPLASH=1`.
8. В обычном режиме — ждёт PTY, запускает bridge, X11, activity, prefs, watchdog.

### 10.3. Watchdog

`ks_watchdog.sh` в цикле раз в 20 секунд:

- если нет `termux-x11 .*:0` — запускает;
- если нет `KlipperScreen/screen.py` — запускает из venv;
- если это splash-режим и прошло <35 секунд — не перехватывает фокус у заставки;
- иначе поднимает activity Termux:X11 на передний план;
- логирует в `~/ks_watchdog.log`.

`KS_WATCHDOG_SPLASH=1` — критично: без него watchdog может выдернуть X11 на передний план раньше, чем лаунчер уберёт заставку.

### 10.4. Ручная проверка без перезагрузки

```bash
pkill -f "KlipperScreen/screen.py"
pkill -f "ks_watchdog.sh"
pkill -f "termux-x11 .*:0"
bash ~/.termux/boot/start-klipper.sh
```

Через ~30 секунд:

```bash
tail -40 ~/boot.log
tail -20 ~/ks_watchdog.log
tail -20 ~/ks_run.log
pgrep -af "KlipperScreen/screen.py|termux-x11 .*:0|ks_watchdog.sh"
```

Ожидаемо: X11, screen.py и watchdog живы; в фокусе X11; KlipperScreen рисуется.

### 10.5. Termux:Boot как fallback

Termux:Boot можно поставить, но HiOS может не доставлять `BOOT_COMPLETED`. Если он всё же работает, скрипт из `~/.termux/boot/` запустится сам. Основной путь — HOME-лаунчер, который вызывает тот же скрипт через `RUN_COMMAND`.

---

## 11. Этап 6 — Klipper Launcher и заставка

### 11.1. Зачем отдельный лаунчер

HiOS:

- не доставляет `BOOT_COMPLETED` в Termux:Boot;
- блокирует `startService` в приложение, которое ещё не запущено («Limit startService ... 3rd-to-died»);
- может убивать фоновые процессы.

HOME-активити (`CATEGORY_HOME`) система запускает всегда. Лаунчер:

1. показывает splash overlay;
2. запускает activity Termux, затем возвращает себя на передний план (`startActivities`);
3. отправляет `RUN_COMMAND` в `com.termux.app.RunCommandService`;
4. опрашивает `127.0.0.1:7125`, `:8080`, `:8081`;
5. убирает splash и открывает Termux:X11.

Не пытайтесь батчить через `startActivities` Termux:X11 или bridge вместе с лаунчером: Android отклоняет межпакетный batch. Запускайте их отдельно (bridge — из boot-скрипта, X11 — из boot-скрипта/лаунчера).

### 11.2. Manifest

Ключевое:

```xml
<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="com.termux.permission.RUN_COMMAND" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<queries>
    <package android:name="com.termux" />
    <package android:name="com.termux.x11" />
    <package android:name="dev.klipper.androidbridge" />
</queries>
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:screenOrientation="landscape"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
    android:theme="@android:style/Theme.NoTitleBar.Fullscreen">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

`configChanges` обязателен: при boot Android может создать activity до завершения поворота; без resize overlay останется обрезанным.

### 11.3. Логика MainActivity

- `onCreate`: `FLAG_KEEP_SCREEN_ON`, чёрный фон, fullscreen, `SplashView`, overlay, `startFlow()`.
- `startFlow()`:
  - 15-секундный debounce: повторный HOME в течение 15 секунд игнорируется;
  - `generation++`, `revealed=false`;
  - `showSplashOverlay()`;
  - `wakeTermuxAndReturn()`: `startActivities([TermuxActivity, MainActivity])`;
  - через 3 секунды `sendBootCommand()`;
  - через 3.5 секунды `startReadinessPolling()`.
- `showSplashOverlay()`:
  - `TYPE_APPLICATION_OVERLAY` (O+) или `TYPE_PHONE`;
  - flags: `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_KEEP_SCREEN_ON | FLAG_NOT_FOCUSABLE`;
  - `MATCH_PARENT` **не использовать**: он даёт ширину app-area (1512 px) из-за навигационной панели;
  - размер — `getRealMetrics()` (1612×720);
  - `layoutInDisplayCutoutMode = ALWAYS` (R+) / `SHORT_EDGES` (P+);
  - `addView`.
- `onConfigurationChanged`: обновить `lp.width/height` по real metrics и `updateViewLayout`.
- `runBootScript()`:
  - intent на `com.termux/.app.RunCommandService`, action `com.termux.RUN_COMMAND`;
  - extras: `RUN_COMMAND_PATH=$PREFIX/bin/bash`, `RUN_COMMAND_ARGUMENTS = [-l, BOOT_SCRIPT, "splash", "bridge"|"nobridge"]`, `RUN_COMMAND_WORKDIR=$HOME`, `RUN_COMMAND_BACKGROUND=true`;
  - `startForegroundService` (O+) / `startService`.
- `hasUsbDevice()`: если `UsbManager.getDeviceList()` не пуст — `bridge`, иначе `nobridge`; при исключении — `bridge` (лучше лишний раз запустить).
- `pollReadiness()`: TCP connect на 7125/8080/8081 с таймаутом 700 мс. Если стек уже был готов на первой проверке (HOME во время печати) — подождать 1.5 с; если порты поднялись позже — 10 с, чтобы KlipperScreen успел отрисоваться; затем `revealUi`. Общий таймаут 150 с; по таймауту всё равно показать UI.
- `revealUi()`: `hideSplashOverlay()`, `openX11()`.
- `onClick`: тап по splash → `revealUi()`.

### 11.4. SplashView

- Фон `#1E1E1E`.
- Логотип `res/drawable/logo.png`: чёрный line-art на прозрачном; инвертируется в белый через `ColorMatrix`, alpha 150.
- Заголовок `KLIPPER` белый, `sans-serif-black`, размер `h*0.14`, центр по Y `h*0.405`.
- Спиннер: кольцо `#454545`, дуга `#3D9BE9`, диаметр `h*0.155`, толщина `h*0.017`, центр `(w*0.5, h*0.603)`, шаг 8° каждые 30 мс.
- Подпись `by: adilo321` моноширинным, `h*0.024`, справа снизу.
- Вся геометрия пропорциональна `w/h`, поэтому работает на других разрешениях.

Чтобы перебрендировать: замените `launcher/res/drawable/logo.png`, строку `"KLIPPER"` и `"by: adilo321"` в `SplashView.java`, пересоберите APK.

### 11.5. Сборка APK

`build.ps1` делает `aapt2 compile` → `aapt2 link` → `javac` → `d8` → `zipalign` → `apksigner`.

Требования:

- JDK (в проекте использовался JDK 27, но для стабильности лучше JDK 17);
- Android SDK build-tools + platform `android.jar`;
- keystore.

Критичные грабли:

- **aapt2/keytool не понимают кириллицу в пути.** Поэтому `build.ps1` копирует исходники в `C:\klipper-build\launcher-src`, собирает в `C:\klipper-build\launcher-build`, а APK кладёт обратно. Не убирайте этот staging.
- **PowerShell 5.1 читает BOM-less `.ps1` как ANSI.** Если скрипт с русскими комментариями, сохраняйте UTF-8 с BOM или используйте base64/ASCII.
- **javac 27 + d8 8.2.2 падают на anonymous inner classes.** В коде используйте lambdas/method refs (`this::sendBootCommand`), а не `new Runnable(){...}`. Либо ставьте JDK 17.
- **Ключ подписи.** В проекте `C:\klipper-build\klipper.keystore`, alias `klipper`. Для публикации сгенерируйте свой; при смене ключа обновление APK потребует удаления приложения.

Сборка:

```powershell
powershell -ExecutionPolicy Bypass -File .\launcher\build.ps1
```

### 11.6. Установка и разрешения

```powershell
& $adb install -r .\launcher\klipper-launcher.apk
& $adb shell "pm grant com.klipper.launcher com.termux.permission.RUN_COMMAND"
& $adb shell "appops set com.klipper.launcher SYSTEM_ALERT_WINDOW allow"
& $adb shell "cmd package set-home-activity com.klipper.launcher/.MainActivity"
```

Проверка HOME:

```powershell
& $adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
```

Должно быть `com.klipper.launcher/.MainActivity`.

Если `set-home-activity` не сработал: Android Settings → Apps → Default apps → Home app → Klipper Launcher.

### 11.7. Проверка лаунчера

```powershell
& $adb shell "am force-stop com.termux"
& $adb shell "input keyevent 3"
Start-Sleep -Seconds 60
& $adb shell "dumpsys window | grep mCurrentFocus"
& $adb logcat -d -s KlipperLauncher | Select-Object -Last 30
```

Ожидаемо: splash, затем X11 в фокусе, KlipperScreen. Если тап не реагирует — проверьте Direct touch (раздел 9.2).

---

## 12. Этап 7 — русский язык, blanking и панель «Сеть»

Этот этап уже описан в 9.6, но повторим порядок действий, потому что это самые частые проблемы:

1. Открыть `~/printer_data/config/KlipperScreen.conf`.
2. В `[main]` поставить `language: ru`, `screen_blanking: off`.
3. В auto-generated секции `#~#` поставить `language = ru`, `screen_blanking = off`, `screen_blanking_printing = off`.
4. Добавить `[menu __splashscreen network] enable: False` и `[menu __main more network] enable: False`.
5. Перезапустить KlipperScreen.
6. Проверить конфиг и лог:

```bash
grep -n "screen_blanking\|language" ~/printer_data/config/KlipperScreen.conf
grep -E "Selected lang|Blanking timeout|Showing Screensaver" ~/ks_run.log | tail -10
```

Ожидаемо: в конфиге `screen_blanking` равен `off` и в `[main]`, и в автосекции `#~#`; в логе есть `Selected lang: ru` и `Blanking timeout: off`, а **после** последнего перезапуска нет новых строк `Showing Screensaver`.

7. Оставить на 90 секунд, снять скриншот — экран не должен гаснуть.
8. Открыть меню — пункта «Сеть» быть не должно; ошибки `sdbus_block` быть не должно.

Если язык всё равно английский:

- проверьте, что в auto-секции именно `ru`, а не `Русский (ru)`;
- проверьте, что KlipperScreen перезапущен, а не просто перечитал конфиг;
- проверьте `~/.KlipperScreen-env` и локаль `ru/LC_MESSAGES/KlipperScreen.mo`;
- в логе `Selected lang:` должно быть `ru`.

Если экран гаснет:

- `screen_blanking = off` в обеих секциях;
- `screen_blanking_printing = off`;
- `use_dpms = False`;
- патч DPMS применён;
- в логе нет `Showing Screensaver`;
- проверьте Android `screen_off_timeout` (не путать с KlipperScreen screensaver).

---

## 13. Этап 8 — конфиг принтера

### 13.1. printer.cfg для Ender 3 V3 SE

Полный файл — в приложении A. Ключевые моменты:

- `[mcu] serial: /data/data/com.termux/files/usr/var/run/klipper-android/main` — это PTY моста, **не** `/dev/ttyACM0`.
- `baud: 250000`, `restart_method: command` — обязательны для PTY.
- `[virtual_sdcard] path: .../printer_data/gcodes` — Mainsail/Fluidd складывают файлы сюда.
- `[bltouch]` с `probe_with_touch_mode: True`, `stow_on_each_sample: False`, `z_offset: 2.65` — стартовое значение, не финальное.
- `[safe_z_home]`, `[bed_mesh]` 5×5, `[screws_tilt_adjust]` — для калибровки.
- `position_endstop: -6` для X и `-14` для Y — не «исправляйте» без понимания геометрии.
- `max_velocity: 250`, `max_accel: 2500` — консервативно; можно поднять после калибровки.

### 13.2. Для другого принтера

Сохраните структуру:

- `[mcu]` — PTY моста и `restart_method: command`;
- `[printer]`, `[stepper_*]`, `[extruder]`, `[heater_bed]`, `[fan]` — из конфига вашей платы;
- `[virtual_sdcard]`, `[pause_resume]`, `[display_status]`, `[respond]`, `[exclude_object]` — для веб-интерфейсов;
- `[bed_mesh]`, `[screws_tilt_adjust]` — если есть probe.

Не копируйте пины Ender 3 V3 SE на другую плату. Ошибка в пинах может включить нагреватель/мотор не туда.

### 13.3. Калибровка после подключения

1. `PROBE_CALIBRATE` → `TESTZ Z=...` → `ACCEPT` → `SAVE_CONFIG`.
2. `BED_MESH_CALIBRATE` → `SAVE_CONFIG`.
3. `SCREWS_TILT_CALCULATE` (если включено) — подкрутить винты, повторить.
4. PID:
   ```gcode
   PID_CALIBRATE HEATER=extruder TARGET=200
   SAVE_CONFIG
   PID_CALIBRATE HEATER=heater_bed TARGET=60
   SAVE_CONFIG
   ```
5. Проверить `z_offset` на первом слое; при необходимости `Z_OFFSET_APPLY_PROBE` / `SAVE_CONFIG`.
6. Только после этого — тестовая печать.

---

## 14. Этап 9 — прошивка MCU

### 14.1. Главное правило

**Klipper-хост и MCU-прошивка должны быть из одного коммита.** В этом проекте — `60fc7aa`. Если обновили хост — пересоберите и перепрошейте MCU. Иначе возможны странные ошибки протокола.

### 14.2. Конфигурация сборки

Для Ender 3 V3 SE / Creality 4.2.2 (GD32F303), сборка как STM32F103xe:

- Micro-controller Architecture: **STMicroelectronics STM32**
- Processor model: **STM32F103**
- Bootloader offset: **28KiB bootloader** (`0x8007000`)
- Clock Reference: **8 MHz crystal**
- Communication interface: **Serial (on USART1 PA9/PA10)**
- Baud rate: **250000**
- GPIO pins at startup: пусто

Ключевые строки `.config`:

```ini
CONFIG_MACH_STM32=y
CONFIG_BOARD_DIRECTORY="stm32"
CONFIG_MCU="stm32f103xe"
CONFIG_CLOCK_FREQ=72000000
CONFIG_SERIAL=y
CONFIG_FLASH_SIZE=0x10000
CONFIG_FLASH_BOOT_ADDRESS=0x8000000
CONFIG_RAM_START=0x20000000
CONFIG_RAM_SIZE=0x5000
CONFIG_FLASH_APPLICATION_ADDRESS=0x8007000
CONFIG_STM32_FLASH_START_7000=y
CONFIG_STM32_CLOCK_REF_8M=y
CONFIG_CLOCK_REF_FREQ=8000000
CONFIG_STM32_SERIAL_USART1=y
CONFIG_SERIAL_BAUD=250000
```

Полный `.config` — в приложении I. Он важен: не собирайте «на глаз», особенно bootloader offset и flash size.

### 14.3. Сборка

На ПК с ARM GNU toolchain:

```bash
git clone https://github.com/Klipper3d/klipper.git
cd klipper
git checkout 60fc7aa
# положить .config из приложения I
make olddefconfig
make -j"$(nproc)"
ls -l out/klipper.bin
sha256sum out/klipper.bin
```

Ожидаемо:

```text
out/klipper.bin  40160 bytes
SHA256 FD8C61A21F06A87C4BA077FF6DA44FE584F6E19349218B3EE61E7E6A57D25CA3
```

На телефоне можно использовать `klctl firmware`, но для нестандартной платы проще собрать на ПК и скопировать `.bin` на SD.

### 14.4. Прошивка через microSD

1. Отформатировать microSD в **FAT32** (не exFAT/NTFS).
2. Скопировать `.bin` в корень карты. Имя должно заканчиваться на `.bin` и **отличаться** от предыдущей прошивки; для первой попытки удобно `firmware.bin`, если такого файла нет. Если загрузчик игнорирует файл — переименуйте (например, `klipper-v3se.bin`) и повторите.
3. Выключить принтер.
4. Вставить карту.
5. Включить принтер.
6. Подождать 10–30 секунд. Экран принтера может остаться тёмным/показывать заставку — это нормально.
7. Выключить принтер, вынуть карту.
8. Включить принтер и подключить OTG.

Не выключайте питание во время прошивки. Если прошивка не встала:

- проверьте FAT32 и размер кластера;
- попробуйте другую карту (некоторые большие SD не читаются загрузчиком);
- проверьте ревизию платы и bootloader offset;
- проверьте, что файл — именно `klipper.bin` из `out`, а не `.elf`/`.dict`;
- не пытайтесь прошивать «наугад» другую плату.

### 14.5. Официальный firmware builder

`thingsapart/klipper_termux` умеет:

```bash
klctl firmware profiles
klctl firmware info <profile>
klctl firmware toolchain-install
klctl firmware build <profile>
klctl firmware export <build-id> downloads
klctl firmware export <build-id> /storage/XXXX-XXXX
```

Если профиль вашей платы есть — используйте его. Если нет (как для этой Creality 4.2.2), собирайте вручную по 14.2–14.3.

---

## 15. Этап 10 — USB/OTG и мост

### 15.1. Установка приложения

Установите **Klipper For Android** (`dev.klipper.androidbridge`) из Releases `thingsapart/klipper_termux`. Это не просто «USB bridge»: приложение содержит foreground service, USB host API, WebView Mainsail и мастер настройки.

### 15.2. bridge.conf

Файл `~/printer_data/config/bridge.conf` создаётся установщиком/приложением. Формат:

```ini
server=127.0.0.1
port=27831
token=<TOKEN>
log_interval=60
device=main,auto,250000,8,1,none,dtr+rts,/data/data/com.termux/files/usr/var/run/klipper-android/main
```

- `server=127.0.0.1` — только loopback, не слушать наружу.
- `token` — секрет; не публикуйте.
- `device=main,auto,250000,...` — PTY `main`, авто-выбор первого разрешённого USB-serial, 250000 бод.
- Если несколько USB-устройств, `auto` может выбрать не то; укажите конкретный порт/устройство по документации проекта.

### 15.3. Подключение

1. Подключите принтер к телефону через USB-C OTG.
2. На телефоне появится диалог «Разрешить доступ к USB-устройству?» — разрешите.
3. Откройте **Klipper For Android** и убедитесь, что bridge service запущен.
4. Проверьте PTY:

```bash
ls -l $PREFIX/var/run/klipper-android/main
sv status klipper-android-bridge
```

5. В Mainsail/Fluidd/KlipperScreen должен появиться `Ready` (или `mcu: Unable to connect`, если прошивка/порт не те).

### 15.4. Если принтер не виден

- Кабель OTG data-capable, не charge-only.
- Телефон поддерживает USB host (OTG) — проверьте `dumpsys usb`.
- Плата потребляет больше, чем даёт телефон — powered hub.
- USB-разрешение не выдано — откройте приложение bridge, подключите заново.
- В `bridge.conf` неверный токен/порт — пересоздайте через приложение.
- `klipper` ждёт PTY — проверьте `ls $PREFIX/var/run/klipper-android/main`.
- `mcu 'mcu': Unable to connect` — прошивка не та, не тот baud, не тот serial, либо bridge не открыл порт.

**Не пытайтесь создать `/dev/ttyACM0` вручную.** На Android без root это не тот путь; Klipper должен смотреть на PTY.

---

## 16. Этап 11 — финальная проверка

### 16.1. Чек-лист

- [ ] `sv status klipper moonraker klipper-web klipper-android-bridge` — все `run`.
- [ ] `curl 127.0.0.1:7125`, `:8080`, `:8081` — отвечают.
- [ ] `pgrep -af "KlipperScreen/screen.py|termux-x11 .*:0|ks_watchdog.sh"` — все живы.
- [ ] KlipperScreen на русском: главный экран, меню, ошибки.
- [ ] Экран не гаснет 90+ секунд.
- [ ] Тап открывает меню; палец пользователя тоже работает.
- [ ] В меню нет «Сеть»; нет ошибки `sdbus_block`.
- [ ] Заставка появляется при boot и исчезает, когда порты готовы (или через 150 с).
- [ ] Повторный HOME не запускает второй boot-скрипт (15-секундный debounce, PID-lock).
- [ ] После реальной перезагрузки всё поднимается само.
- [ ] Принтер `Ready`, температуры/энстопы/BLTouch адекватны.
- [ ] `z_offset` откалиброван, bed mesh снят.
- [ ] Тестовая печать прошла, thermal runaway не сработал.

### 16.2. Реальная перезагрузка

Перед reboot:

```powershell
& $adb shell "dumpsys battery | grep -E 'level|status|powered'"
```

Если заряд <30% — заряжайте. Затем:

```powershell
& $adb reboot
```

После boot:

```powershell
& $adb wait-for-device
Start-Sleep -Seconds 45
& $adb shell "dumpsys window | grep mCurrentFocus"
& $adb shell "screencap -p /sdcard/boot_check.png"
& $adb pull /sdcard/boot_check.png .\boot_check.png
```

Проверьте `~/boot.log`: должен быть один запуск, без «another start is already running». Если два — debounce/PID-lock не сработали.

### 16.3. Что смотреть в логах

```bash
tail -50 ~/boot.log
tail -50 ~/ks_run.log
tail -50 ~/ks_watchdog.log
tail -50 ~/x11.log
tail -50 ~/printer_data/logs/klippy.log
tail -50 ~/printer_data/logs/moonraker.log
tail -50 ~/printer_data/logs/nginx-error.log
```

Android:

```powershell
& $adb logcat -d -s KlipperLauncher
& $adb logcat -d | Select-String -Pattern "termux|klipper|x11" | Select-Object -Last 50
```

---

## 17. Диагностика: симптом → причина → лечение

### 17.1. Заставка

| Симптом | Причина | Лечение |
|---|---|---|
| Заставка висит >150 с | порты не поднялись, лаунчер ждёт таймаут | проверить `sv status`, порты, логи; тап по заставке снимает её |
| Заставка мигает/обрезана справа | `MATCH_PARENT` вместо real metrics | использовать `getRealMetrics`, flags `LAYOUT_IN_SCREEN/NO_LIMITS`, `NOT_FOCUSABLE` |
| Заставка не появляется | нет `SYSTEM_ALERT_WINDOW` | `appops set ... SYSTEM_ALERT_WINDOW allow` |
| После заставки чёрный экран | X11/KlipperScreen не стартовали | проверить `x11.log`, `ks_run.log`, Direct touch, blanking |
| Два boot-скрипта | повторный HOME/быстрый запуск | 15-секундный debounce в launcher + PID-lock в скрипте |
| Watchdog крадёт фокус у заставки | `KS_WATCHDOG_SPLASH` не выставлен | запускать watchdog с `KS_WATCHDOG_SPLASH=1`, 35-секундная задержка |

### 17.2. KlipperScreen

| Симптом | Причина | Лечение |
|---|---|---|
| Чёрный экран, процесс жив | screensaver/DPMS, `screen_blanking = 60` в auto-секции | `screen_blanking = off` в обеих секциях, `use_dpms=False`, патч DPMS |
| Английский UI | `language = Русский (ru)` в auto-секции | `language = ru` в обеих секциях, перезапуск |
| Тач двигает курсор, кнопки не нажимаются | Termux:X11 в Trackpad | `termux-x11-preference "touchMode:Direct touch"`, перезапуск X11 |
| Ошибка при открытии «Сеть» | нет `sdbus_block`/NetworkManager | скрыть пункт меню, Wi-Fi через Android |
| Падение на Spoolman | динамический SVG-градиент | патч статичной иконки |
| Падение на батарее | `psutil.sensors_battery()` запрещён | try/except, скрыть виджет |
| Падение на CPU/RAM | Moonraker не отдаёт proc stats | безопасный `.get()` |
| `xset: not found` | нет xset/libXext | патч `FileNotFoundError`, stub DPMS |
| KlipperScreen не стартует | не тот venv/Python/GTK | `--system-site-packages`, проверить `import gi`, `DISPLAY=:0` |
| `Unable to load panel network` | `sdbus_block` | скрыть меню |

### 17.3. Автозапуск и Android

| Симптом | Причина | Лечение |
|---|---|---|
| Termux:Boot не работает | HiOS блокирует `BOOT_COMPLETED` | HOME-лаунчер + `RUN_COMMAND` |
| `startService` падает | HiOS «3rd-to-died» | сначала стартовать activity Termux, затем RUN_COMMAND |
| `am` не запускает activity | `/system/bin/am` из-под app UID | `$PREFIX/bin/am` |
| HOME не становится лаунчером | не выбран default home | `cmd package set-home-activity`, Android Settings |
| Лаунчер убивается | battery optimization | снять оптимизацию, автозапуск, lock в recents |
| USB-мост не стартует | нет USB-разрешения/приложения | открыть Klipper For Android, разрешить USB |
| После debloat нет HOME | удалили лаунчер/SystemUI | `pm install-existing`, вернуть пакеты |

### 17.4. Klipper/Moonraker/nginx

| Симптом | Причина | Лечение |
|---|---|---|
| `Timer too close` | RAW clock на старом ядре | применить патч Klipper из установщика, пересобрать `c_helper.so` |
| Moonraker сыпет systemd/DBus | нет `[machine] provider: none` | добавить, перезапустить |
| Mainsail 502/WebSocket fail | nginx не передаёт Host/Upgrade | `proxy_set_header Host $http_host`, `Upgrade`/`Connection` |
| Fluidd 404 | нет второго server 8081 или неверный root | добавить server, `try_files`, перезапустить `klipper-web` |
| `klipper` ждёт PTY | bridge не запущен/нет USB | проверить bridge, PTY, USB permission |
| `mcu: Unable to connect` | прошивка/baud/serial/bridge | проверить firmware, 250000, PTY, bridge.conf |
| Сервис `down` | `sv start` не выполнен | `sv start ...`, проверить `down`-файлы |
| `service-daemon start` не поднимает | сервисы с `down` | `sv start` вручную/в boot-скрипте |

### 17.5. Прошивка/принтер

| Симптом | Причина | Лечение |
|---|---|---|
| Принтер не прошивается | не FAT32/не тот файл/карта | FAT32, `firmware.bin`, другая карта, проверить bootloader offset |
| После прошивки экран тёмный | нормально | подождать 10–30 с, выключить, вынуть карту |
| `mcu: Unable to connect` после прошивки | хост/ MCU commit не совпал, baud, serial | пересобрать из 60fc7aa, проверить PTY/250000 |
| Принтер не виден по USB | charge-only кабель/нет OTG/нет разрешения | data-кабель, OTG, USB permission, powered hub |
| `/dev/ttyACM0` нет | так и должно быть | использовать PTY, не искать ttyACM |
| Не работает BLTouch | пины/режим probe | сверить printer.cfg с платой, `probe_with_touch_mode` |
| `z_offset` неправильный | стартовое 2.65 | `PROBE_CALIBRATE`, `SAVE_CONFIG` |

---

## 18. Правила для ИИ: как не сломать установку

1. **Сначала спроси, потом делай.** Модель телефона, Android, принтер, плата, заряд, наличие SD/OTG.
2. **Один этап — одна проверка.** Не переходи дальше, пока не увидел ожидаемый вывод.
3. **Не удаляй данные без бэкапа.** `printer_data`, `printer.cfg`, `KlipperScreen.conf`, `bridge.conf`, keystore.
4. **Не прошивай MCU без подтверждения ревизии платы.** Ошибка bootloader offset может «окирпичить» плату.
5. **Не обновляй хост без пересборки прошивки.** Держи `60fc7aa` для хоста и MCU.
6. **Не используй `/system/bin/am`.** Только `$PREFIX/bin/am`.
7. **Не ищи `/dev/ttyACM0`.** Только PTY.
8. **Не выключай питание во время прошивки.**
9. **Не проверяй заряд после reboot.** Проверь до.
10. **Не публикуй секреты.** IP, серийник, токен, пароль SSH, keystore.
11. **Не открывай порты в интернет.** Moonraker/nginx — для локальной сети.
12. **Не оставляй принтер без присмотра** до проверки thermal runaway и энстопов.
13. **Проверяй тач пальцем.** `adb input tap` не доказывает, что дигитайзер работает.
14. **Сохраняй логи.** `boot.log`, `ks_run.log`, `klippy.log`, `moonraker.log`, `logcat`.
15. **Если не работает — остановись.** Собери 2–3 гипотезы, проверь по одной, не «пробуй всё подряд».

### 18.1. Адаптация под другое железо

| Параметр | Что менять |
|---|---|
| Телефон/экран | разрешение, cutout, orientation, `wm size`; overlay использует real metrics, X11 prefs — exact |
| Android/HiOS | автозапуск, battery optimization, HOME role; на AOSP/LineageOS Termux:Boot может работать без лаунчера |
| Принтер | `printer.cfg` (пины, кинематика, probe, mesh) |
| Плата MCU | Klipper `.config`: MCU, bootloader offset, clock, serial/USB, baud |
| Прошивка | собрать из того же коммита, что хост |
| USB | powered hub, кабель, `bridge.conf` device selector |
| Язык | `language: ru` (или другой код) в обеих секциях |
| Заставка | `SplashView.java`, `logo.png`, credit |

### 18.2. Ограничения и риски

- Старый телефон — слабый CPU/мало RAM; KlipperScreen + nginx + Moonraker могут подтормаживать. Не запускайте тяжёлые приложения.
- Батарея изношена; при питании от USB может не хватать тока. Используйте charger и powered hub.
- Экран AMOLED/IPS может выгорать от статичной картинки; kiosk-режим — на свой риск.
- Android может убить процессы при нехватке памяти; watchdog помогает, но не гарантирует.
- Wi-Fi mDNS на некоторых роутерах не работает; используйте IP.
- Klipper не является системой безопасности; thermal fuse/thermostat принтера должны быть исправны.

---

## 19. Быстрый чек-лист установки

```text
[ ] Termux + Termux:X11 + Klipper For Android + Klipper Launcher установлены
[ ] allow-external-apps=true, termux-reload-settings
[ ] battery optimization off, autostart on, screen timeout max
[ ] официальный installer klipper_termux выполнен, klctl doctor OK
[ ] Moonraker 7125, Mainsail 8080, Fluidd 8081 отвечают
[ ] Termux:X11 запущен, Direct touch/fullscreen/landscape/exact
[ ] KlipperScreen установлен, патчи применены, language=ru, blanking=off
[ ] network menu скрыт
[ ] boot-скрипт + watchdog + start_klipperscreen на месте, chmod +x
[ ] launcher APK собран/установлен, RUN_COMMAND + overlay + HOME
[ ] splash появляется и снимается по готовности
[ ] printer.cfg с PTY, restart_method=command
[ ] прошивка собрана из 60fc7aa, 40160 bytes, SHA256 совпал
[ ] microSD FAT32, прошивка залита, принтер перезагружен
[ ] OTG, USB permission, bridge PTY, mcu Ready
[ ] z_offset/bed mesh/PID откалиброваны
[ ] реальная перезагрузка: splash, autostart, RU, touch, no blank
[ ] тестовая печать под присмотром
```

---

## Приложения

Ниже — полные файлы и конфиги, чтобы гайд был самодостаточным. Если что-то расходится с вашей платой/телефоном — не копируйте слепо, адаптируйте.

## Приложение A. `printer.cfg` для Ender 3 V3 SE

```ini
# Ender 3 V3 SE / Creality 4.2.2 (GD32F303) + Klipper on Android
# MCU link is the klipper-android bridge PTY.
[mcu]
serial: /data/data/com.termux/files/usr/var/run/klipper-android/main
baud: 250000
restart_method: command

[printer]
kinematics: cartesian
max_velocity: 250
max_accel: 2500
max_z_velocity: 5
square_corner_velocity: 5.0
max_z_accel: 100

[virtual_sdcard]
path: /data/data/com.termux/files/home/printer_data/gcodes

[pause_resume]

[display_status]

[respond]

[exclude_object]

[stepper_x]
step_pin: PC2
dir_pin: !PB9
enable_pin: !PC3
microsteps: 16
rotation_distance: 40
endstop_pin: ~!PA5
position_endstop: -6
position_min: -6
position_max: 230
homing_speed: 80

[tmc2208 stepper_x]
uart_pin: PB12
run_current: 0.60
sense_resistor: 0.150
interpolate: True

[stepper_y]
step_pin: PB8
dir_pin: PB7
enable_pin: !PC3
microsteps: 16
rotation_distance: 40
endstop_pin: ~!PA6
position_endstop: -14
position_min: -14
position_max: 230
homing_speed: 80

[tmc2208 stepper_y]
uart_pin: PB13
run_current: 0.60
sense_resistor: 0.150
interpolate: True

[stepper_z]
step_pin: PB6
dir_pin: !PB5
enable_pin: !PC3
microsteps: 16
rotation_distance: 8
endstop_pin: probe:z_virtual_endstop
position_min: -3
position_max: 250
homing_speed: 4
second_homing_speed: 1
homing_retract_dist: 2.0

[tmc2208 stepper_z]
uart_pin: PB14
run_current: 0.8
sense_resistor: 0.150
interpolate: True

[extruder]
max_extrude_only_distance: 100.0
step_pin: PB4
dir_pin: PB3
enable_pin: !PC3
microsteps: 16
rotation_distance: 7.44
nozzle_diameter: 0.400
filament_diameter: 1.750
heater_pin: PA1
sensor_type: EPCOS 100K B57560G104F
sensor_pin: PC5
control: pid
pid_Kp: 27.142
pid_Ki: 1.371
pid_Kd: 134.351
min_temp: 0
max_temp: 260

[heater_bed]
heater_pin: PB2
sensor_type: EPCOS 100K B57560G104F
sensor_pin: PC4
control: pid
pid_kp: 66.371
pid_ki: 0.846
pid_kd: 1301.702
min_temp: 0
max_temp: 100

[heater_fan hotend_fan]
pin: PC1

[fan]
pin: PA0

[bltouch]
sensor_pin: ^PC14
control_pin: PC13
x_offset: -23.0
y_offset: -14.5
z_offset: 2.65
speed: 20
pin_move_time: 0.4
stow_on_each_sample: False
probe_with_touch_mode: True

[safe_z_home]
home_xy_position: 134,123
speed: 150
z_hop: 10
z_hop_speed: 10

[bed_mesh]
speed: 120
horizontal_move_z: 5
mesh_min: 30,30
mesh_max: 207,215.5
probe_count: 5,5
algorithm: bicubic

[output_pin beeper]
pin: PB0

[screws_tilt_adjust]
screw1: 54.5, 45.5
screw1_name: front left screw
screw2: 224.5, 45.5
screw2_name: front right screw
screw3: 224.5, 215.5
screw3_name: rear right screw
screw4: 54.5, 215.5
screw4_name: rear left screw
horizontal_move_z: 10.
speed: 50.
screw_thread: CCW-M4
```

> `z_offset: 2.65` — только стартовое значение. После первого подключения обязательно `PROBE_CALIBRATE` и `SAVE_CONFIG`.

---

## Приложение B. `moonraker.conf`

```ini
[server]
host: 0.0.0.0
port: 7125
klippy_uds_address: /data/data/com.termux/files/home/printer_data/comms/klippy.sock

[file_manager]
check_klipper_config_path: True
enable_config_write_access: True

# Android/Termux has no systemd or system D-Bus. Moonraker's API and file
# manager remain available; host reboot and service-control APIs are disabled.
[machine]
provider: none
validate_service: False
validate_config: False

[authorization]
trusted_clients:
    127.0.0.0/8
    10.0.0.0/8
    172.16.0.0/12
    192.168.0.0/16

[zeroconf]
mdns_hostname: klipper-android
```

---

## Приложение C. `nginx.conf` (Mainsail :8080, Fluidd :8081)

```nginx
daemon off;
worker_processes 1;
error_log /data/data/com.termux/files/home/printer_data/logs/nginx-error.log;
pid /data/data/com.termux/files/home/printer_data/nginx.pid;

events { worker_connections 128; }
http {
    include /data/data/com.termux/files/usr/etc/nginx/mime.types;
    access_log /data/data/com.termux/files/home/printer_data/logs/nginx-access.log;
    server {
        listen 8080;
        server_name _;
        root /data/data/com.termux/files/home/mainsail;
        index index.html;
        location = /firmware { return 301 /firmware/; }
        location /firmware/ {
            alias /data/data/com.termux/files/home/printer_data/gcodes/firmware/;
            index index.html;
            autoindex off;
            default_type application/octet-stream;
            add_header X-Content-Type-Options nosniff always;
        }
        location / { try_files $uri $uri/ /index.html; }
        location /websocket {
            proxy_pass http://127.0.0.1:7125/websocket;
            proxy_http_version 1.1;
            proxy_set_header Host $http_host;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "Upgrade";
        }
        location ~ ^/(printer|api|access|machine|server)/ {
            proxy_pass http://127.0.0.1:7125$request_uri;
            proxy_set_header Host $http_host;
        }
    }
    server {
        listen 8081;
        server_name _;
        root /data/data/com.termux/files/home/fluidd;
        index index.html;
        location = /firmware { return 301 /firmware/; }
        location /firmware/ {
            alias /data/data/com.termux/files/home/printer_data/gcodes/firmware/;
            index index.html;
            autoindex off;
            default_type application/octet-stream;
            add_header X-Content-Type-Options nosniff always;
        }
        location / { try_files $uri $uri/ /index.html; }
        location /websocket {
            proxy_pass http://127.0.0.1:7125/websocket;
            proxy_http_version 1.1;
            proxy_set_header Host $http_host;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "Upgrade";
        }
        location ~ ^/(printer|api|access|machine|server)/ {
            proxy_pass http://127.0.0.1:7125$request_uri;
            proxy_set_header Host $http_host;
        }
    }
}
```

---

## Приложение D. `KlipperScreen.conf`

```ini
[main]
language: ru
screen_blanking: off

[printer default]
moonraker_host: 127.0.0.1
moonraker_port: 7125
[menu __splashscreen network]
enable: False

[menu __main more network]
enable: False

#~# --- Do not edit below this line. This section is auto generated --- #~#
#~#
#~# [main]
#~# screen_blanking = off
#~# screen_blanking_printing = off
#~# font_size = large
#~# confirm_estop = True
#~# use_dpms = False
#~# show_heater_power = True
#~# language = ru
#~#
```

---

## Приложение E. `start-klipper.sh`

```bash
#!/data/data/com.termux/files/usr/bin/bash
# Autostart for Klipper + KlipperScreen on Android.
# Called by the HOME launcher (with the "splash" argument) or by Termux:Boot.
#
# In splash mode the launcher shows its splash overlay, this script starts
# Termux:X11 and the watchdog below it, and the launcher removes the overlay
# once Moonraker and the web UIs are ready.
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export PATH=$PREFIX/bin:$PATH
export LD_LIBRARY_PATH=$PREFIX/lib
export TMPDIR=$PREFIX/tmp
export SVDIR=$PREFIX/var/service
export LOGDIR=$PREFIX/var/log

SPLASH_MODE="${1:-}"

exec >> "$HOME/boot.log" 2>&1
echo "=== $(date '+%F %T') Klipper start (${SPLASH_MODE:-normal}) ==="

# The system may deliver HOME twice within a few seconds. Do not let two
# copies of this script race each other (double X11 start, double splash
# reassert). The PID is checked so a crashed run cannot leave a stale lock.
START_LOCK="$HOME/.klipper-start.pid"
if [ -f "$START_LOCK" ] && kill -0 "$(cat "$START_LOCK" 2>/dev/null)" 2>/dev/null; then
    echo "another start is already running (pid $(cat "$START_LOCK")), exiting"
    exit 0
fi
echo $$ > "$START_LOCK"
trap 'rm -f "$START_LOCK"' EXIT

# Keep the CPU awake while on charger
termux-wake-lock >/dev/null 2>&1

# Termux:X11 stores its settings in app-private data. Re-assert the ones the
# kiosk needs on every boot; "Direct touch" is essential, otherwise the screen
# behaves like a trackpad (taps only move an invisible pointer and
# KlipperScreen looks unresponsive). Needs the X11 app process to be running,
# so call this after starting its activity.
fix_x11_prefs() {
    termux-x11-preference \
        "touchMode:Direct touch" \
        "hideCutout:true" \
        "forceOrientation:landscape" \
        "fullscreen:true" \
        "displayResolutionMode:exact" \
        "adjustResolution:false" \
        "pointerCapture:false" >/dev/null 2>&1
    echo "x11 prefs: $(termux-x11-preference list 2>/dev/null | grep touchMode)"
}

# When the launcher asks for the splash and everything is already running
# (HOME pressed while printing), just make sure the watchdog is alive and
# let the launcher reveal X11.
if [ "$SPLASH_MODE" = "splash" ] \
   && curl -s -o /dev/null --max-time 2 http://127.0.0.1:7125/ \
   && pgrep -f "KlipperScreen/screen.py" >/dev/null; then
    echo "stack already running, splash will reveal X11"
    fix_x11_prefs
    if ! pgrep -f "ks_watchdog.sh" >/dev/null; then
        KS_WATCHDOG_SPLASH=1 nohup "$PREFIX/bin/bash" "$HOME/ks_watchdog.sh" \
            > "$HOME/ks_watchdog.log" 2>&1 &
        echo "watchdog started (splash mode)"
    fi
    exit 0
fi

# Klipper, Moonraker, Mainsail/Fluidd, USB bridge (runit)
service-daemon start >/dev/null 2>&1
# Services are marked "down" by default when installed with sv-enable-less
# setups; start them explicitly so the UI does not show "Connection refused".
sv start klipper moonraker klipper-web klipper-android-bridge >/dev/null 2>&1

# Splash mode: the launcher owns the splash. Start the USB bridge (if the
# launcher reported an attached device) and Termux:X11 *below* it. Starting
# the X11 activity (not a headless server) makes Termux:X11 apply its saved
# screen size before KlipperScreen connects. Then put the splash back on top.
if [ "$SPLASH_MODE" = "splash" ]; then
    BRIDGE_ARG="${2:-nobridge}"
    if [ "$BRIDGE_ARG" = "bridge" ]; then
        am start -n dev.klipper.androidbridge/.MainActivity
        echo "bridge am rc=$?"
        # Give the bridge a moment to create the printer PTY
        for i in $(seq 1 10); do
            [ -e "$PREFIX/var/run/klipper-android/main" ] && break
            sleep 1
        done
    fi

    # Termux:X11's activity only attaches to a running server. Start the
    # server binary first, then let the activity apply its saved screen size.
    if ! pgrep -f "termux-x11 .*:0" >/dev/null; then
        nohup "$PREFIX/bin/termux-x11" :0 -ac > "$HOME/x11.log" 2>&1 &
    fi
    sleep 3
    am start -n com.termux.x11/.MainActivity
    echo "x11 am rc=$?"
    sleep 3
    fix_x11_prefs
    am start -n com.termux.x11/.MainActivity
    echo "x11 reassert rc=$?"
    sleep 3

    if ! pgrep -f "ks_watchdog.sh" >/dev/null; then
        KS_WATCHDOG_SPLASH=1 nohup "$PREFIX/bin/bash" "$HOME/ks_watchdog.sh" \
            > "$HOME/ks_watchdog.log" 2>&1 &
        echo "watchdog started (splash mode)"
    fi
    exit 0
fi

# --- Normal mode (Termux:Boot fallback, or a manual run) ---

# Give the bridge a moment to create the printer PTY
for i in $(seq 1 30); do
    [ -e "$PREFIX/var/run/klipper-android/main" ] && break
    sleep 1
done

# Bring the USB bridge app up (may require one-time USB permission).
# Use Termux's `am` wrapper: /system/bin/am fails for app UIDs on Android 12+.
am start -n dev.klipper.androidbridge/.MainActivity
echo "bridge am rc=$?"

# X server used by KlipperScreen
if ! pgrep -f "termux-x11 .*:0" >/dev/null; then
    nohup "$PREFIX/bin/termux-x11" :0 -ac > "$HOME/x11.log" 2>&1 &
fi
sleep 3

# Show the X11 display so Termux:X11 applies its screen preferences before
# KlipperScreen queries the X server; re-assert once in case another activity
# (e.g. the USB permission dialog) grabbed focus.
am start -n com.termux.x11/.MainActivity
echo "x11 am rc=$?"
sleep 3
fix_x11_prefs
am start -n com.termux.x11/.MainActivity
echo "x11 reassert rc=$?"

# Restart KlipperScreen if it dies
if ! pgrep -f "ks_watchdog.sh" >/dev/null; then
    nohup "$PREFIX/bin/bash" "$HOME/ks_watchdog.sh" \
        > "$HOME/ks_watchdog.log" 2>&1 &
    echo "watchdog started"
fi
```

---

## Приложение F. `ks_watchdog.sh`

```bash
#!/data/data/com.termux/files/usr/bin/bash
# KlipperScreen watchdog: keeps X server and UI alive
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export PATH=$PREFIX/bin:$PATH
export LD_LIBRARY_PATH=$PREFIX/lib
export TMPDIR=$PREFIX/tmp
export DISPLAY=:0
export XDG_RUNTIME_DIR=$PREFIX/var/run

# In splash mode the boot launcher keeps its animation on top and opens
# Termux:X11 itself once the stack is ready. Do not steal focus during the
# first 35 seconds; after that (or for later crash restarts) re-assert X11.
SPLASH_MODE="${KS_WATCHDOG_SPLASH:-0}"
START_TS=$(date +%s)

while true; do
    if ! pgrep -f "termux-x11 .*:0" >/dev/null; then
        nohup "$PREFIX/bin/termux-x11" :0 -ac >> "$HOME/x11.log" 2>&1 &
        sleep 3
    fi
    if ! pgrep -f "KlipperScreen/screen.py" >/dev/null; then
        cd "$HOME/KlipperScreen" || exit 1
        nohup "$HOME/.KlipperScreen-env/bin/python" -X faulthandler \
            "$HOME/KlipperScreen/screen.py" >> "$HOME/ks_run.log" 2>&1 &
        sleep 6
        elapsed=$(( $(date +%s) - START_TS ))
        if [ "$SPLASH_MODE" = "1" ] && [ "$elapsed" -lt 35 ]; then
            echo "splash active (${elapsed}s), leaving launcher in front"
        else
            # Bring the X11 display back to the foreground after a restart
            "$PREFIX/bin/am" start -n com.termux.x11/.MainActivity >/dev/null 2>&1
            sleep 4
        fi
    fi
    sleep 20
done
```

---

## Приложение G. `start_klipperscreen.sh`

```bash
#!/data/data/com.termux/files/usr/bin/bash
# Manual start of the KlipperScreen UI (expects termux-x11 to be available).
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export PATH=$PREFIX/bin:$PATH
export LD_LIBRARY_PATH=$PREFIX/lib
export TMPDIR=$PREFIX/tmp
export DISPLAY=:0
export XDG_RUNTIME_DIR=$PREFIX/var/run

# Start the X server if it is not running yet
if ! pgrep -f "termux-x11 .*:0" >/dev/null; then
    nohup "$PREFIX/bin/termux-x11" :0 -ac > "$HOME/x11.log" 2>&1 &
    sleep 3
fi

# Bring the Termux:X11 window to the foreground (Termux's am wrapper works
# from app UIDs, unlike /system/bin/am on Android 12+)
"$PREFIX/bin/am" start -n com.termux.x11/.MainActivity >/dev/null 2>&1

# If KlipperScreen is already running, just show it
if pgrep -f "KlipperScreen/screen.py" >/dev/null; then
    exit 0
fi

cd "$HOME/KlipperScreen" || exit 1
exec "$HOME/.KlipperScreen-env/bin/python" -X faulthandler "$HOME/KlipperScreen/screen.py" "$@"
```

---

## Приложение H. `patch_ks.py`

```python
#!/data/data/com.termux/files/usr/bin/python
"""Patch KlipperScreen for Android/Termux quirks (idempotent)."""
import pathlib

KS = pathlib.Path("/data/data/com.termux/files/home/KlipperScreen")

# 1) Spoolman dynamic SVG gradient crashes librsvg on Termux -> use static icon
p = KS / "panels" / "base_panel.py"
s = p.read_text()
if "Termux workaround: dynamic SVG" not in s:
    needle = "        icon_size = self._gtk.img_scale * self.bts * 0.9\n"
    repl = (
        needle
        + "        # Termux workaround: dynamic SVG gradient rendering crashes librsvg\n"
        + '        return self._gtk.PixbufFromIcon("spool", icon_size, icon_size)\n'
    )
    s = s.replace(needle, repl, 1)
    p.write_text(s)
    print("patched spool icon")

# 2) psutil battery access is denied on Android -> hide the battery widget
if 'Battery information not available: {e}' not in s:
    needle = "        battery = psutil.sensors_battery()\n"
    repl = (
        "        try:\n"
        "            battery = psutil.sensors_battery()\n"
        "        except Exception as e:\n"
        '            logging.debug(f"Battery information not available: {e}")\n'
        '            self.control["battery_box"].hide()\n'
        "            return False\n"
    )
    s = s.replace(needle, repl, 1)
    p.write_text(s)
    print("patched battery")

# 3) Moonraker has no proc-stats provider on Android -> safe CPU/RAM parse
if 'system_cpu_usage") or {}' not in s:
    old = (
        '            cpu = data["system_cpu_usage"]["cpu"]\n'
        '            memory = (data["system_memory"]["used"] / data["system_memory"]["total"]) * 100\n'
    )
    new = (
        '            usage = data.get("system_cpu_usage") or {}\n'
        '            cpu = usage.get("cpu", 0)\n'
        '            mem = data.get("system_memory") or {}\n'
        '            memory = (mem.get("used", 0) / (mem.get("total") or 1)) * 100\n'
    )
    if old in s:
        s = s.replace(old, new, 1)
        p.write_text(s)
        print("patched cpu stats")
    else:
        print("cpu stats pattern not found")

# 4) DPMS: no xset/libXext on Termux -> stub + ignore missing xset
p = KS / "ks_includes" / "functions.py"
s = p.read_text()
if 'return DPMS_State.Fail' not in s:
    old = (
        "except OSError as e:\n"
        '    logging.error(f"Couldn\'t load DPMS library: {e}")\n'
        "except Exception as e:\n"
        '    logging.error(f"An unexpected error occurred: {e}")\n'
    )
    stub = (
        "    class DPMS_State:\n"
        "        Fail = -1\n"
        "        On = 0\n"
        "        Standby = 1\n"
        "        Suspend = 2\n"
        "        Off = 3\n"
        '    def get_DPMS_state(display_name_in_byte_string=b":0"):\n'
        "        return DPMS_State.Fail\n"
    )
    new = (
        "except OSError as e:\n"
        '    logging.error(f"Couldn\'t load DPMS library: {e}")\n'
        + stub
        + "except Exception as e:\n"
        '    logging.error(f"An unexpected error occurred: {e}")\n'
        + stub
    )
    if old in s:
        s = s.replace(old, new, 1)
        p.write_text(s)
        print("patched DPMS")
    else:
        print("DPMS pattern not found")

p = KS / "screen.py"
s = p.read_text()
if "xset not available" not in s:
    old = (
        '            cmd = ["xset", "-display", self.display_number, "s", "off"]\n'
        "            subprocess.call(cmd)\n"
        '            cmd = ["xset", "-display", self.display_number, "s", "noblank"]\n'
        "            subprocess.call(cmd)\n"
    )
    new = (
        '            cmd = ["xset", "-display", self.display_number, "s", "off"]\n'
        "            try:\n"
        "                subprocess.call(cmd)\n"
        "            except FileNotFoundError:\n"
        '                logging.debug("xset not available")\n'
        '            cmd = ["xset", "-display", self.display_number, "s", "noblank"]\n'
        "            try:\n"
        "                subprocess.call(cmd)\n"
        "            except FileNotFoundError:\n"
        '                logging.debug("xset not available")\n'
    )
    if old in s:
        s = s.replace(old, new, 1)
        p.write_text(s)
        print("patched xset")
    else:
        print("xset pattern not found")

print("done")
```

---

## Приложение I. Полный `.config` прошивки MCU

Сборка для Ender 3 V3 SE (плата Creality 4.2.2 на GD32F303, собирается как STM32F103xe) из Klipper `60fc7aa`. Файл получен командой `make menuconfig` и проверен сборкой: `out/klipper.bin` = 40 160 байт, SHA256 `FD8C61A21F06A87C4BA077FF6DA44FE584F6E19349218B3EE61E7E6A57D25CA3`.

Ключевые параметры, которые нельзя менять «на глаз»:

- `CONFIG_MCU="stm32f103xe"` — совместимость с GD32F303;
- `CONFIG_FLASH_APPLICATION_ADDRESS=0x8007000` и `CONFIG_STM32_FLASH_START_7000=y` — 28 KiB bootloader;
- `CONFIG_STM32_CLOCK_REF_8M=y` — кварц 8 МГц;
- `CONFIG_STM32_SERIAL_USART1=y` — PA9/PA10;
- `CONFIG_SERIAL_BAUD=250000`.

```ini
CONFIG_LOW_LEVEL_OPTIONS=y
# CONFIG_MACH_AVR is not set
# CONFIG_MACH_ATSAM is not set
# CONFIG_MACH_ATSAMD is not set
# CONFIG_MACH_LPC176X is not set
CONFIG_MACH_STM32=y
# CONFIG_MACH_HC32F460 is not set
# CONFIG_MACH_RPXXXX is not set
# CONFIG_MACH_PRU is not set
# CONFIG_MACH_AR100 is not set
# CONFIG_MACH_LINUX is not set
# CONFIG_MACH_SIMU is not set
CONFIG_BOARD_DIRECTORY="stm32"
CONFIG_MCU="stm32f103xe"
CONFIG_CLOCK_FREQ=72000000
CONFIG_SERIAL=y
CONFIG_FLASH_SIZE=0x10000
CONFIG_FLASH_BOOT_ADDRESS=0x8000000
CONFIG_RAM_START=0x20000000
CONFIG_RAM_SIZE=0x5000
CONFIG_STACK_SIZE=512
CONFIG_FLASH_APPLICATION_ADDRESS=0x8007000
CONFIG_STM32_SELECT=y
CONFIG_MACH_STM32F103=y
# CONFIG_MACH_STM32F207 is not set
# CONFIG_MACH_STM32F401 is not set
# CONFIG_MACH_STM32F405 is not set
# CONFIG_MACH_STM32F407 is not set
# CONFIG_MACH_STM32F429 is not set
# CONFIG_MACH_STM32F446 is not set
# CONFIG_MACH_STM32F765 is not set
# CONFIG_MACH_STM32F031 is not set
# CONFIG_MACH_STM32F042 is not set
# CONFIG_MACH_STM32F070 is not set
# CONFIG_MACH_STM32F072 is not set
# CONFIG_MACH_STM32G070 is not set
# CONFIG_MACH_STM32G071 is not set
# CONFIG_MACH_STM32G0B0 is not set
# CONFIG_MACH_STM32G0B1 is not set
# CONFIG_MACH_STM32G431 is not set
# CONFIG_MACH_STM32G474 is not set
# CONFIG_MACH_STM32H723 is not set
# CONFIG_MACH_STM32H743 is not set
# CONFIG_MACH_STM32H750 is not set
# CONFIG_MACH_STM32L412 is not set
# CONFIG_MACH_N32G452 is not set
# CONFIG_MACH_N32G455 is not set
# CONFIG_MACH_STM32F103x6 is not set
CONFIG_MACH_STM32F1=y
CONFIG_HAVE_STM32_USBFS=y
CONFIG_STM32_USB_DOUBLE_BUFFER_TX=y
CONFIG_HAVE_STM32_CANBUS=y
# CONFIG_STM32F103GD_DISABLE_SWD is not set
CONFIG_STM32_DFU_ROM_ADDRESS=0
# CONFIG_STM32_FLASH_START_2000 is not set
# CONFIG_STM32_FLASH_START_5000 is not set
CONFIG_STM32_FLASH_START_7000=y
# CONFIG_STM32_FLASH_START_8000 is not set
# CONFIG_STM32_FLASH_START_8800 is not set
# CONFIG_STM32_FLASH_START_9000 is not set
# CONFIG_STM32_FLASH_START_10000 is not set
# CONFIG_STM32_FLASH_START_800 is not set
# CONFIG_STM32_FLASH_START_1000 is not set
# CONFIG_STM32_FLASH_START_4000 is not set
# CONFIG_STM32_FLASH_START_0000 is not set
CONFIG_STM32_CLOCK_REF_8M=y
# CONFIG_STM32_CLOCK_REF_12M is not set
# CONFIG_STM32_CLOCK_REF_16M is not set
# CONFIG_STM32_CLOCK_REF_20M is not set
# CONFIG_STM32_CLOCK_REF_24M is not set
# CONFIG_STM32_CLOCK_REF_25M is not set
# CONFIG_STM32_CLOCK_REF_INTERNAL is not set
CONFIG_CLOCK_REF_FREQ=8000000
CONFIG_STM32F0_TRIM=16
# CONFIG_STM32_USB_PA11_PA12 is not set
CONFIG_STM32_SERIAL_USART1=y
# CONFIG_STM32_SERIAL_USART1_ALT_PB7_PB6 is not set
# CONFIG_STM32_SERIAL_USART2 is not set
# CONFIG_STM32_SERIAL_USART2_ALT_PD6_PD5 is not set
# CONFIG_STM32_SERIAL_USART3 is not set
# CONFIG_STM32_SERIAL_USART3_ALT_PD9_PD8 is not set
# CONFIG_STM32_CANBUS_PA11_PA12 is not set
# CONFIG_STM32_CANBUS_PA11_PB9 is not set
# CONFIG_STM32_MMENU_CANBUS_PB8_PB9 is not set
# CONFIG_STM32_MMENU_CANBUS_PD0_PD1 is not set
CONFIG_SERIAL_BAUD=250000
CONFIG_USB_VENDOR_ID=0x1d50
CONFIG_USB_DEVICE_ID=0x614e
CONFIG_USB_SERIAL_NUMBER="12345"
CONFIG_WANT_ADC=y
CONFIG_WANT_SPI=y
CONFIG_WANT_SOFTWARE_SPI=y
CONFIG_WANT_I2C=y
CONFIG_WANT_SOFTWARE_I2C=y
CONFIG_WANT_HARD_PWM=y
CONFIG_WANT_BUTTONS=y
CONFIG_WANT_TMCUART=y
CONFIG_WANT_NEOPIXEL=y
CONFIG_WANT_PULSE_COUNTER=y
CONFIG_WANT_ST7920=y
CONFIG_WANT_HD44780=y
CONFIG_WANT_ADXL345=y
CONFIG_WANT_LIS2DW=y
CONFIG_WANT_BMI160=y
CONFIG_WANT_MPU9250=y
CONFIG_WANT_ICM20948=y
CONFIG_WANT_THERMOCOUPLE=y
CONFIG_WANT_HX71X=y
CONFIG_WANT_ADS131M0X=y
CONFIG_WANT_ADS1220=y
CONFIG_WANT_LDC1612=y
CONFIG_WANT_SENSOR_ANGLE=y
CONFIG_NEED_SENSOR_BULK=y
CONFIG_WANT_TRIGGER_ANALOG=y
CONFIG_NEED_SOS_FILTER=y
CONFIG_CANBUS_FREQUENCY=1000000
CONFIG_INLINE_STEPPER_HACK=y
CONFIG_HAVE_STEPPER_OPTIMIZED_BOTH_EDGE=y
CONFIG_WANT_STEPPER_OPTIMIZED_BOTH_EDGE=y
CONFIG_INITIAL_PINS=""
CONFIG_HAVE_GPIO=y
CONFIG_HAVE_GPIO_ADC=y
CONFIG_HAVE_GPIO_SPI=y
CONFIG_HAVE_GPIO_I2C=y
CONFIG_HAVE_GPIO_HARD_PWM=y
CONFIG_HAVE_STRICT_TIMING=y
CONFIG_HAVE_CHIPID=y
CONFIG_HAVE_BOOTLOADER_REQUEST=y
```

---

## Приложение J. Лаунчер: `AndroidManifest.xml` и `build.ps1`

### J.1. `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.klipper.launcher"
    android:versionCode="1"
    android:versionName="1.0">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34" />

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="com.termux.permission.RUN_COMMAND" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

    <queries>
        <package android:name="com.termux" />
        <package android:name="com.termux.x11" />
        <package android:name="dev.klipper.androidbridge" />
    </queries>

    <application
        android:allowBackup="false"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="Klipper Launcher">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
            android:theme="@android:style/Theme.NoTitleBar.Fullscreen">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

### J.2. `build.ps1`

Скрипт собирает APK через `aapt2 → javac → d8 → zipalign → apksigner`. Пути `C:\klipper-build\...` — пример; замените на свои. Пароль keystore `klipper123` — локальный демонстрационный, для публикации сгенерируйте свой ключ и пароль.

```powershell
$ErrorActionPreference = "Continue"

$SDK = "C:\klipper-build\android-sdk"
$BT = Join-Path $SDK "build-tools"
$PLATFORM = Join-Path $SDK "platform"
$ANDROID_JAR = Join-Path $PLATFORM "android.jar"
$KEYTOOL = "C:\Program Files\Java\jdk-27\bin\keytool.exe"

# aapt2/keytool do not cope with non-ASCII paths, so stage everything under C:\
$PROJECT = $PSScriptRoot
$SRC = "C:\klipper-build\launcher-src"
$BUILD = "C:\klipper-build\launcher-build"
$KS = "C:\klipper-build\klipper.keystore"
$OUT = Join-Path $PROJECT "klipper-launcher.apk"

if (!(Test-Path $ANDROID_JAR)) { throw "android.jar not found: $ANDROID_JAR" }

Remove-Item $SRC -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $BUILD -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $SRC | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\dex" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\res" | Out-Null

Copy-Item "$PROJECT\AndroidManifest.xml" "$SRC\AndroidManifest.xml"
Copy-Item "$PROJECT\src" "$SRC\src" -Recurse
Copy-Item "$PROJECT\res" "$SRC\res" -Recurse

Write-Host "== aapt2 compile =="
& "$BT\aapt2.exe" compile --dir "$SRC\res" -o "$BUILD\res\compiled.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== aapt2 link =="
& "$BT\aapt2.exe" link -o "$BUILD\app-unsigned.apk" `
    --manifest "$SRC\AndroidManifest.xml" `
    -I "$ANDROID_JAR" `
    --min-sdk-version 24 --target-sdk-version 34 `
    --version-code 1 --version-name 1.0 `
    --java "$BUILD\gen" `
    "$BUILD\res\compiled.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== javac =="
$javaFiles = @(
    Get-ChildItem "$SRC\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    Get-ChildItem "$BUILD\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
)
& javac -source 8 -target 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$BUILD\classes" $javaFiles *> "$BUILD\javac.log"
if ($LASTEXITCODE -ne 0) {
    Get-Content "$BUILD\javac.log"
    throw "javac failed"
}

Write-Host "== d8 =="
$classes = Get-ChildItem "$BUILD\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --lib "$ANDROID_JAR" --min-api 24 --output "$BUILD\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== add classes.dex =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$BUILD\app-unsigned.apk", "Update")
$entry = $zip.GetEntry("classes.dex")
if ($entry) { $entry.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$BUILD\dex\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()

Write-Host "== zipalign =="
& "$BT\zipalign.exe" -f 4 "$BUILD\app-unsigned.apk" "$BUILD\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (!(Test-Path $KS)) {
    Write-Host "== keytool (new keystore) =="
    & "$KEYTOOL" -genkeypair -keystore "$KS" -alias klipper -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass klipper123 -keypass klipper123 `
        -dname "CN=Klipper Launcher, OU=Klipper, O=Klipper, L=Home, ST=Home, C=RU"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "== apksigner =="
& "$BT\apksigner.bat" sign --ks "$KS" --ks-key-alias klipper `
    --ks-pass pass:klipper123 --key-pass pass:klipper123 `
    --out "$OUT" "$BUILD\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$BT\apksigner.bat" verify --print-certs "$OUT" | Select-Object -First 4
Write-Host "== built: $OUT =="
Get-Item $OUT | Select-Object FullName, Length
```

Критичные грабли:

- `aapt2` и `keytool` не понимают кириллицу в пути — поэтому исходники копируются в `C:\klipper-build\launcher-src`, сборка идёт в `C:\klipper-build\launcher-build`.
- PowerShell 5.1 читает `.ps1` без BOM как ANSI. Если добавляете русские комментарии — сохраняйте файл в UTF-8 **с BOM**.
- `javac 27` + `d8 8.2.2` падают на anonymous inner classes. Используйте лямбды/method reference (`this::sendBootCommand`) или JDK 17.
- При смене ключа подписи обновление поверх установленного APK невозможно — приложение нужно удалить.

Логотип заставки: `launcher/res/drawable/logo.png` (чёрный line-art на прозрачном фоне; в коде инвертируется в белый).

---

## Приложение K. `MainActivity.java`

```java
package com.klipper.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.usb.UsbManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * HOME activity for the dedicated Klipper phone.
 *
 * Shows a splash screen while the host stack starts, then reveals
 * Termux:X11 with KlipperScreen. The splash stays on top until Moonraker
 * and both web UIs answer on localhost (or the timeout expires).
 */
public class MainActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "KlipperLauncher";

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_ACTIVITY = "com.termux.app.TermuxActivity";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";

    private static final String X11_PACKAGE = "com.termux.x11";
    private static final String X11_ACTIVITY = "com.termux.x11.MainActivity";

    private static final String HOME_DIR = "/data/data/com.termux/files/home";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String BOOT_SCRIPT = HOME_DIR + "/.termux/boot/start-klipper.sh";

    private static final int MOONRAKER_PORT = 7125;
    private static final int MAINSAIL_PORT = 8080;
    private static final int FLUIDD_PORT = 8081;
    private static final int READY_TIMEOUT_MS = 150000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sendCommandTask = this::sendBootCommand;
    private final Runnable startPollTask = this::startReadinessPolling;
    private final Runnable revealTask = this::revealUi;

    private volatile boolean revealed = false;
    private volatile int generation = 0;
    private long lastFlowStart = 0;

    private WindowManager windowManager;
    private WindowManager.LayoutParams splashParams;
    private View splashOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setBackgroundDrawableResource(android.R.color.black);
        useFullDisplay(window);

        setContentView(new SplashView(this));
        hideSystemUi();
        startFlow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        hideSystemUi();

        // Only a real HOME press should restart the boot flow; plain
        // "am start" calls just bring the splash back.
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            startFlow();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // The launcher is landscape-only, but at boot Android may create it
        // before the rotation completes. Keep the overlay sized to the real
        // display so it never ends up cropped or letterboxed.
        if (splashOverlay != null && windowManager != null && splashParams != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            splashParams.width = metrics.widthPixels;
            splashParams.height = metrics.heightPixels;
            try {
                windowManager.updateViewLayout(splashOverlay, splashParams);
            } catch (Throwable t) {
                Log.e(TAG, "unable to resize the splash overlay", t);
            }
        }
        hideSystemUi();
    }

    @Override
    public void onClick(View v) {
        // Tapping the splash always brings the UI back.
        Log.i(TAG, "splash tapped");
        revealUi();
    }

    /** Begin (or restart) the wait-for-services flow. */
    private void startFlow() {
        // The system may deliver HOME again right after the activity is
        // created; do not run the boot script twice in a row.
        long now = System.currentTimeMillis();
        if (now - lastFlowStart < 15000) {
            Log.i(TAG, "duplicate HOME within 15s, ignoring");
            return;
        }
        lastFlowStart = now;

        handler.removeCallbacksAndMessages(null);
        generation++;
        revealed = false;

        // The overlay keeps the splash above Termux:X11 while the host
        // starts, so X11 can stay in the foreground with the right size.
        showSplashOverlay();

        wakeTermuxAndReturn();
        handler.postDelayed(sendCommandTask, 3000);
        handler.postDelayed(startPollTask, 3500);
    }

    /** Add the splash as a system overlay on top of Termux:X11. */
    private void showSplashOverlay() {
        if (splashOverlay != null) return;
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            SplashView view = new SplashView(this);
            view.setOnClickListener(this);
            view.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            // Do not steal focus from Termux:X11/the activity:
                            // otherwise the system bars reappear and the
                            // overlay is confined to the app area (1512 px).
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;

            // MATCH_PARENT is resolved against the app area (the navigation
            // bar strip is excluded). Use the real display size instead so
            // the splash covers the whole 1612x720 panel.
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            lp.width = metrics.widthPixels;
            lp.height = metrics.heightPixels;
            lp.x = 0;
            lp.y = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            windowManager.addView(view, lp);
            splashOverlay = view;
            splashParams = lp;
            Log.i(TAG, "splash overlay added: " + lp.width + "x" + lp.height);
        } catch (Throwable t) {
            // Without the overlay permission the activity splash is still
            // shown; only Termux:X11 may flash above it during boot.
            Log.e(TAG, "unable to add the splash overlay", t);
            splashOverlay = null;
        }
    }

    private void hideSplashOverlay() {
        if (splashOverlay == null) return;
        try {
            windowManager.removeView(splashOverlay);
        } catch (Throwable t) {
            Log.e(TAG, "unable to remove the splash overlay", t);
        }
        splashOverlay = null;
        splashParams = null;
    }

    /** Let the splash draw under the status bar and over the camera cutout. */
    private void useFullDisplay(Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            window.setAttributes(lp);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
    }

    private void hideSystemUi() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /**
     * Start Termux below the home activity so its process exists and HiOS
     * allows starting its service from the foreground afterwards.
     */
    private void wakeTermuxAndReturn() {
        Intent termux = componentIntent(TERMUX_PACKAGE, TERMUX_ACTIVITY);

        Intent self = new Intent(this, MainActivity.class);
        self.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        try {
            startActivities(new Intent[]{termux, self});
            Log.i(TAG, "Termux activity started below home");
        } catch (Throwable t) {
            Log.e(TAG, "unable to start Termux activity", t);
            try {
                startActivity(termux);
            } catch (Throwable ignored) {
            }
        }
    }

    private Intent componentIntent(String pkg, String activity) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(pkg, activity));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private boolean hasUsbDevice() {
        try {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            return manager != null && !manager.getDeviceList().isEmpty();
        } catch (Throwable t) {
            // If enumeration fails, start the bridge anyway: a working
            // printer is more important than a short splash flicker.
            return true;
        }
    }

    private void sendBootCommand() {
        boolean ok = runBootScript();
        Log.i(TAG, "boot script requested: " + ok);
    }

    private boolean runBootScript() {
        try {
            Intent intent = new Intent();
            intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
            intent.setAction(RUN_COMMAND_ACTION);
            intent.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
            // "splash" tells the script to keep X11 in the background; the
            // launcher opens it once the stack reports ready.
            // "splash" tells the script to keep X11 in the background.
            // "bridge"/"nobridge" says whether a USB device is attached, so
            // boot does not flash the bridge window for nothing.
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                    new String[]{"-l", BOOT_SCRIPT, "splash",
                            hasUsbDevice() ? "bridge" : "nobridge"});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME_DIR);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "unable to send RUN_COMMAND to Termux", t);
            return false;
        }
    }

    private void startReadinessPolling() {
        final int gen = generation;
        Thread thread = new Thread(() -> pollReadiness(gen), "klipper-ready");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Wait until Moonraker and both web UIs listen on localhost, give
     * KlipperScreen a few seconds to draw, then reveal X11. A timeout
     * guarantees that the splash never traps the user forever.
     */
    private void pollReadiness(int gen) {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        boolean firstCheck = true;

        while (gen == generation && !revealed && System.currentTimeMillis() < deadline) {
            if (stackReady()) {
                // If everything was already up when the splash appeared
                // (HOME pressed while printing), do not keep the user waiting.
                // Otherwise allow KlipperScreen time to draw behind the splash.
                sleepQuiet(firstCheck ? 1500 : 10000);
                if (gen == generation) {
                    handler.post(revealTask);
                }
                return;
            }
            firstCheck = false;
            sleepQuiet(1200);
        }

        if (gen == generation && !revealed) {
            Log.w(TAG, "readiness timeout, revealing the UI anyway");
            handler.post(revealTask);
        }
    }

    private boolean stackReady() {
        return canConnect(MOONRAKER_PORT)
                && canConnect(MAINSAIL_PORT)
                && canConnect(FLUIDD_PORT);
    }

    private boolean canConnect(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 700);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void revealUi() {
        if (revealed) return;
        revealed = true;
        hideSplashOverlay();
        openX11();
    }

    private void openX11() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(X11_PACKAGE, X11_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            Log.i(TAG, "Termux:X11 opened");
        } catch (Throwable t) {
            Log.e(TAG, "unable to open Termux:X11", t);
        }
    }
}
```

---

## Приложение L. `SplashView.java`

```java
package com.klipper.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Full-screen boot splash: printer line art on the left, KLIPPER wordmark
 * in the middle, rotating spinner below it and the author credit in the
 * bottom-right corner. All geometry is proportional to the screen size so
 * it looks the same on any phone in landscape.
 */
public class SplashView extends View {
    private static final int BG = Color.rgb(0x1E, 0x1E, 0x1E);
    private static final int TRACK = Color.rgb(0x45, 0x45, 0x45);
    private static final int ACCENT = Color.rgb(0x3D, 0x9B, 0xE9);
    private static final int CREDIT = Color.rgb(0x8C, 0x8C, 0x8C);

    private final Paint logoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint creditPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF logoDst = new RectF();
    private final RectF spinner = new RectF();

    private final Bitmap logo;
    private float titleCenterY;
    private float creditRight;
    private float creditBaseline;
    private float angle;
    private final Runnable frame = this::tick;

    public SplashView(Context context) {
        super(context);
        setBackgroundColor(BG);

        logo = BitmapFactory.decodeResource(getResources(), R.drawable.logo);
        // The artwork is black line art on transparency; invert it to white.
        ColorMatrix invert = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
        });
        logoPaint.setColorFilter(new ColorMatrixColorFilter(invert));
        logoPaint.setAlpha(150);

        titlePaint.setColor(Color.WHITE);
        titlePaint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setShadowLayer(10f, 0f, 4f, 0x99000000);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(TRACK);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(ACCENT);

        creditPaint.setColor(CREDIT);
        creditPaint.setTypeface(Typeface.MONOSPACE);
        creditPaint.setTextAlign(Paint.Align.RIGHT);
        creditPaint.setLetterSpacing(0.08f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        titlePaint.setTextSize(h * 0.14f);
        titleCenterY = h * 0.405f;

        float diameter = h * 0.155f;
        float stroke = Math.max(4f, h * 0.017f);
        float cx = w * 0.5f;
        float cy = h * 0.603f;
        spinner.set(cx - diameter / 2f, cy - diameter / 2f,
                cx + diameter / 2f, cy + diameter / 2f);
        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);

        if (logo != null) {
            float logoH = h * 0.74f;
            float logoW = logoH * logo.getWidth() / logo.getHeight();
            float left = w * 0.005f;
            float top = h * 0.25f;
            logoDst.set(left, top, left + logoW, top + logoH);
            src.set(0, 0, logo.getWidth(), logo.getHeight());
        }

        creditPaint.setTextSize(h * 0.024f);
        creditRight = w - w * 0.03f;
        creditBaseline = h - h * 0.035f - creditPaint.descent();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (logo != null) {
            canvas.drawBitmap(logo, src, logoDst, logoPaint);
        }

        Paint.FontMetrics fm = titlePaint.getFontMetrics();
        float baseline = titleCenterY - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("KLIPPER", getWidth() * 0.5f, baseline, titlePaint);

        float inset = trackPaint.getStrokeWidth() / 2f;
        RectF ring = new RectF(spinner);
        ring.inset(inset, inset);
        canvas.drawArc(ring, 0f, 360f, false, trackPaint);
        canvas.drawArc(ring, angle, 70f, false, arcPaint);

        canvas.drawText("by: adilo321", creditRight, creditBaseline, creditPaint);
    }

    private void tick() {
        angle += 8f;
        if (angle >= 360f) angle -= 360f;
        invalidate();
        postDelayed(frame, 30);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(frame);
        post(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
}
```

---

## Приложение M. Ссылки, шпаргалка команд и шаблон отчёта

### M.1. Ссылки

| Что | Адрес |
|---|---|
| Базовый проект Klipper на Android | `https://github.com/thingsapart/klipper_termux` |
| Klipper | `https://github.com/Klipper3d/klipper` |
| Moonraker | `https://github.com/Arksine/moonraker` |
| Mainsail | `https://github.com/mainsail-crew/mainsail` |
| Fluidd | `https://github.com/fluidd-core/fluidd` |
| KlipperScreen | `https://github.com/KlipperScreen/KlipperScreen` |
| Termux | `https://github.com/termux/termux-app` |
| Termux:X11 | `https://github.com/termux/termux-x11` |
| Android platform-tools | `https://developer.android.com/studio/releases/platform-tools` |
| Документация Klipper | `https://www.klipper3d.org/` |

### M.2. Шпаргалка Termux

```bash
# сервисы
sv status klipper moonraker klipper-web klipper-android-bridge
sv restart moonraker
sv restart klipper-web
sv start klipper moonraker klipper-web klipper-android-bridge

# порты
ss -ltnp 2>/dev/null | grep -E ':(7125|8080|8081)\b'
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:7125/
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8081/

# процессы UI
pgrep -af "termux-x11 .*:0|KlipperScreen/screen.py|ks_watchdog.sh"

# логи
tail -f ~/boot.log
tail -f ~/ks_run.log
tail -f ~/ks_watchdog.log
tail -f ~/printer_data/logs/klippy.log
tail -f ~/printer_data/logs/moonraker.log

# перезапуск UI вручную
pkill -f "KlipperScreen/screen.py"
bash ~/start_klipperscreen.sh

# полный прогон boot-скрипта без перезагрузки
pkill -f "KlipperScreen/screen.py"
pkill -f "ks_watchdog.sh"
pkill -f "termux-x11 .*:0"
bash ~/.termux/boot/start-klipper.sh

# настройки X11
termux-x11-preference list | grep -Ei "touchMode|hideCutout|forceOrientation|fullscreen"
termux-x11-preference "touchMode:Direct touch"
```

### M.3. Шпаргалка ADB (Windows PowerShell)

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

& $adb devices -l
& $adb shell "dumpsys battery | grep -E 'level|status|powered'"
& $adb shell "dumpsys window | grep mCurrentFocus"
& $adb shell "input keyevent 3"          # HOME
& $adb shell "am force-stop com.termux"  # проверить холодный автозапуск

# скриншот: только через файл, иначе PowerShell может испортить PNG
& $adb shell "screencap -p /sdcard/ks.png"
& $adb pull /sdcard/ks.png .\ks.png

# тап по координатам (проверка X11, не замена проверки пальцем)
& $adb shell "input tap 1070 620"

# логи лаунчера
& $adb logcat -d -s KlipperLauncher

# перезагрузка (только при заряде >= 30-40%)
& $adb reboot
& $adb wait-for-device
```

### M.4. Шаблон отчёта о проверке

```text
Дата:
Телефон:                 TECNO BF6 / <модель>
Android / прошивка:      Android 12 (API 31) / HiOS <версия>
Экран:                   720x1612, landscape 1612x720
Принтер:                 Ender 3 V3 SE
Плата MCU:               Creality 4.2.2 / GD32F303
Klipper host commit:     60fc7aa
Firmware SHA256:         FD8C61A21F06A87C4BA077FF6DA44FE584F6E19349218B3EE61E7E6A57D25CA3
KlipperScreen commit:    34610ce
Moonraker commit:        d5ee171
Mainsail / Fluidd:       v2.18.2 / v1.37.7

[ ] Moonraker 7125 отвечает
[ ] Mainsail 8080 отвечает
[ ] Fluidd 8081 отвечает
[ ] KlipperScreen запущен, язык ru
[ ] Экран не гаснет 90+ секунд
[ ] Тап пальцем открывает меню
[ ] Панель «Сеть» скрыта
[ ] Заставка появляется при boot
[ ] Заставка исчезает после готовности портов
[ ] Повторный HOME не запускает второй boot
[ ] Реальная перезагрузка поднимает стек автоматически
[ ] Принтер определяется по OTG
[ ] MCU в состоянии Ready
[ ] z_offset откалиброван
[ ] BED_MESH_CALIBRATE выполнен
[ ] Тестовая печать прошла

Замечания:
```

### M.5. Что делать дальше

1. Дождаться microSD FAT32 и USB-C OTG.
2. Залить `klipper-ender3v3se-60fc7aa.bin` на карту, прошить принтер, вынуть карту.
3. Подключить OTG, разрешить USB-доступ, дождаться `Ready`.
4. Откалибровать `z_offset`, снять bed mesh, прогнать PID.
5. Проверить нагрев/термисторы/энстопы и только потом печатать.
6. После стабилизации — заменить стартовые PID/`z_offset` на реальные из `SAVE_CONFIG`.

---

*Конец файла. Если вы читаете это как ИИ-агент: не начинайте с команд — сначала задайте вопросы из раздела 0.2 и проверьте заряд телефона.*

