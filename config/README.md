# Конфиги

Скопируйте файлы в Termux:

```bash
mkdir -p ~/printer_data/config
cp printer.cfg moonraker.conf nginx.conf KlipperScreen.conf ~/printer_data/config/
```

`bridge.conf` создаётся приложением **Klipper For Android** и содержит
секретный токен. Не копируйте `bridge.conf.example` как есть: замените
`<TOKEN>` на реальный токен из приложения (или дайте приложению создать
файл самому).

```bash
cp bridge.conf.example ~/printer_data/config/bridge.conf
# затем отредактируйте token=...
```

`printer.cfg` рассчитан на Ender 3 V3 SE (Creality 4.2.2 / GD32F303).
Для другой платы не копируйте пины — адаптируйте конфиг.

После копирования:

```bash
sv restart klipper moonraker klipper-web
```

Полная инструкция — в `../AI-INSTALL-GUIDE.md`.
