#!/data/data/com.termux/files/usr/bin/bash
# Apply remaining Termux-side setup after the patches/scripts are pushed.
set -e
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export PATH=$PREFIX/bin:$PATH
export LD_LIBRARY_PATH=$PREFIX/lib
export TMPDIR=$PREFIX/tmp
export DISPLAY=:0
export XDG_RUNTIME_DIR=$PREFIX/var/run

chmod +x "$HOME/ks_watchdog.sh" 2>/dev/null || true
chmod +x "$HOME/.termux/boot/start-klipper.sh" 2>/dev/null || true

# Restart cleanly
pkill -f "KlipperScreen/screen.py" 2>/dev/null || true
pkill -f "termux-x11 .*:0" 2>/dev/null || true
sleep 2
nohup "$PREFIX/bin/termux-x11" :0 -ac > "$HOME/x11.log" 2>&1 &
sleep 3
nohup "$PREFIX/bin/bash" "$HOME/ks_watchdog.sh" > "$HOME/ks_watchdog.log" 2>&1 &
sleep 12
pgrep -af "KlipperScreen/screen.py" || true
tail -12 "$HOME/ks_run.log" 2>/dev/null || true
