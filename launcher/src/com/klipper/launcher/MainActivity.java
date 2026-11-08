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
