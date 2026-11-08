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
