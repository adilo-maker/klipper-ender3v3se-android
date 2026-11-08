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
