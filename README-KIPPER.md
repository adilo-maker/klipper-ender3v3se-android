# Klipper на Android — шпаргалка

## Что уже работает
- **Mainsail**: http://PHONE_IP:8080
- **Fluidd**: http://PHONE_IP:8081
- **Moonraker**: http://PHONE_IP:7125
- Klipper, Moonraker, nginx, USB-мост — под runit (`sv status klipper moonraker klipper-web klipper-android-bridge`)

## Прошивка принтера (Ender 3 V3 SE)
Файл: `klipper-ender3v3se-60fc7aa.bin` (в этой папке)

1. Отформатируйте microSD в FAT32.
2. Скопируйте `.bin` в корень карты. Имя может быть любым, но **должно отличаться** от имени прошлой прошивки и заканчиваться на `.bin`.
3. Выключите принтер, вставьте карту, включите принтер.
4. Подождите 10–30 секунд: экран принтера может остаться тёмным/показывать заставку — это нормально.
5. Выключите принтер, выньте карту.

Прошивка собрана из того же коммита Klipper (`60fc7aa`), что и хост на телефоне — протокол совпадает.

## Подключение
1. Соедините телефон и принтер кабелем **USB-C OTG**.
2. На телефоне при первом подключении разрешите доступ к USB-устройству (диалог Android).
3. В приложении **Klipper USB Bridge** убедитесь, что мост запущен.
4. В Mainsail/Fluidd/in KlipperScreen появится статус `Ready`.

## Конфиг
`~/printer_data/config/printer.cfg` — уже прописан Ender 3 V3 SE:
- плата Creality 4.2.2, GD32F303 (сборка как STM32F103)
- BLTouch/CR-Touch, TMC2208, bed mesh 5×5, screws tilt
- serial: PTY USB-моста

После первой печати обязательно откалибруйте:
- `z_offset` (сейчас 2.65 — стартовое значение)
- `PROBE_CALIBRATE`, `BED_MESH_CALIBRATE`
- PID сопла/стола при желании

## Автозапуск
- **Klipper Launcher** (`com.klipper.launcher`) — назначен домашним экраном Android. При загрузке он показывает заставку, поднимает Termux и через `RUN_COMMAND` запускает `~/.termux/boot/start-klipper.sh` с аргументом `splash`. Это обход блокировки Termux:Boot со стороны HiOS.
  - APK собирается из исходников в `launcher/` командой `launcher/build.ps1` (готовый APK в репозиторий не входит)
  - Разрешения: `com.termux.permission.RUN_COMMAND`, `SYSTEM_ALERT_WINDOW`, `INTERNET`
  - Заставка: принтер слева, «KLIPPER», синий спиннер, «by: adilo321». Держится, пока не поднимутся Moonraker (7125), Mainsail (8080) и Fluidd (8081), затем снимается, открывая уже запущенный Termux:X11 с KlipperScreen. Через 150 с заставка снимется в любом случае; тап по экрану снимает её сразу.
- `~/.termux/boot/start-klipper.sh` — поднимает runit-сервисы и watchdog, запускает Termux:X11 (и USB Bridge, если лаунчер сообщил о подключённом устройстве), затем выставляет настройки X11. В режиме `splash` всё это происходит под заставкой.
- `~/ks_watchdog.sh` — перезапускает KlipperScreen/X11, если упали. В режиме `KS_WATCHDOG_SPLASH=1` первые 35 секунд не перехватывает фокус у заставки, затем снова выводит X11 на экран.
- `~/start_klipperscreen.sh` — ручной запуск UI (поднимет X-сервер и окно, если нужно).
- В настройках TECNO (HiOS) разрешите автозапуск для **Termux**, **Termux:X11** и **Klipper USB Bridge**, и снимите ограничения батареи.

Повторное нажатие HOME во время печати снова показывает заставку на несколько секунд и возвращает KlipperScreen.

### Если сенсор «не работает»
Termux:X11 должен быть в режиме **Direct touch**, а не **Trackpad**. В Trackpad касания двигают невидимый курсор, поэтому KlipperScreen выглядит не реагирующим на нажатия. Скрипт автозапуска выставляет режим автоматически после старта X11, но проверить/исправить можно вручную:
```bash
termux-x11-preference list | grep touchMode   # должно быть "Direct touch"
termux-x11-preference "touchMode:Direct touch"
```
То же самое доступно в приложении Termux:X11: **Preferences → Pointer → Touchscreen input mode → Direct touch**.

### Язык и гашение экрана
В `~/printer_data/config/KlipperScreen.conf` должны быть обе секции:
```ini
[main]
language: ru
screen_blanking: off
```
KlipperScreen дописывает в конец файла автосохранённую секцию `#~#`; её значения перекрывают пользовательские. Если в ней остались `language = Русский (ru)` или `screen_blanking = 60`, замените их на `language = ru` и `screen_blanking = off` — иначе UI слетает на английский, а экран гаснет через минуту.

### Сеть
Штатная панель «Сеть» в KlipperScreen рассчитана на Linux/NetworkManager (`sdbus_block`) и в Termux падает с `No module named 'sdbus_block'`. Поэтому пункт скрыт в конфиге:
```ini
[menu __splashscreen network]
enable: False

[menu __main more network]
enable: False
```
Wi-Fi управляется средствами Android: шторка сверху или **Настройки → Wi-Fi**. Для принтера это не требуется — Moonraker и веб-интерфейсы работают по локальной сети телефона.

Важно: на Android 12 `/system/bin/am` не работает из-под UID приложения. Скрипты используют Termux-wrapper `$PREFIX/bin/am` — не заменяйте его на системный.

Важно: HiOS блокирует `startService` в приложение, которое ещё не запущено («3rd-to-died»). Поэтому лаунчер сначала стартует активити Termux, затем возвращает себя на передний план и только потом шлёт `RUN_COMMAND`.

## Проверка автозапуска
```bash
# имитация загрузки без перезагрузки телефона
pkill -f KlipperScreen/screen.py; pkill -f ks_watchdog.sh; pkill -f "termux-x11 .*:0"
bash ~/.termux/boot/start-klipper.sh
```
Через ~30 секунд KlipperScreen должен сам появиться на весь экран в landscape.

Если после реальной перезагрузки UI не появился:
1. Откройте Termux и выполните `bash ~/.termux/boot/start-klipper.sh`.
2. Проверьте, что Klipper Launcher выбран домашним экраном: `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`.
3. Проверьте лог: `tail -50 ~/boot.log` и `tail -50 ~/ks_run.log`.

## Полезные команды (Termux)
```bash
sv status klipper moonraker klipper-web klipper-android-bridge
sv restart klipper
tail -f ~/printer_data/logs/klippy.log
tail -f ~/printer_data/logs/moonraker.log
tail -f ~/ks_run.log
~/start_klipperscreen.sh          # ручной запуск UI
```

## Вернуть удалённые приложения TECNO
```bash
pm install-existing <package>
```
Список удалённого — в файле `removed-packages.txt`. Самые важные: `com.transsion.magicshow`, `com.transsion.powercenter`, `com.transsion.fmradio`, `com.google.android.videos`.

## Примечания
- KlipperScreen пропатчен под Termux: статичная иконка Spoolman, безопасное чтение батареи, stub DPMS, игнор отсутствующего `xset`.
- Пока принтер не подключён, Moonraker показывает `klippy_state: startup` — это ожидаемо.
