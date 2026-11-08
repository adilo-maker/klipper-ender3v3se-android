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
