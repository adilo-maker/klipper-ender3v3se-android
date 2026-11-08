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
