# Прошивка MCU

Файл `klipper-ender3v3se-60fc7aa.bin` собран из Klipper `60fc7aa` для
Ender 3 V3 SE (плата Creality 4.2.2 / GD32F303, сборка как STM32F103xe).

- Размер: 40 160 байт
- SHA256: `FD8C61A21F06A87C4BA077FF6DA44FE584F6E19349218B3EE61E7E6A57D25CA3`
- Bootloader offset: 28 KiB (`0x8007000`)
- Clock: 8 MHz crystal
- Serial: USART1 (PA9/PA10), 250000 baud

## Прошивка через microSD

1. Отформатируйте карту в **FAT32**.
2. Скопируйте `.bin` в корень карты. Имя должно заканчиваться на `.bin` и
   отличаться от предыдущей прошивки. Для первой попытки удобно
   `firmware.bin`, если такого файла ещё нет.
3. Выключите принтер, вставьте карту, включите принтер.
4. Подождите 10–30 секунд. Экран может остаться тёмным — это нормально.
5. Выключите принтер, выньте карту, включите снова.

Не выключайте питание во время прошивки.

## Пересборка из исходников

```bash
git clone https://github.com/Klipper3d/klipper.git
cd klipper
git checkout 60fc7aa
cp /path/to/firmware/config .config
make olddefconfig
make -j"$(nproc)"
sha256sum out/klipper.bin
```

`config` — полный `.config`, использованный для этой сборки. Прошивка
распространяется по GPLv3 (Klipper); исходники доступны в upstream.
