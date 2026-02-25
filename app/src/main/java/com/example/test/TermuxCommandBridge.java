package ai.agent1c.hitomi;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class TermuxCommandBridge {
    public static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_RUN_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_TERMUX_RUN = "com.termux.RUN_COMMAND";
    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN";
    private static final String EXTRA_BG = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    private static final String EXTRA_REQ_ID = "ai.agent1c.hitomi.termux_req_id";
    private static final String RESULT_BUNDLE_KEY = "result";
    private static final String RESULT_STDOUT = "stdout";
    private static final String RESULT_STDERR = "stderr";
    private static final String RESULT_EXIT_CODE = "exitCode";
    private static final String RESULT_ERRMSG = "errmsg";
    private static final String RESULT_ERR = "err";

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger nextReqId = new AtomicInteger(1000);
    private final String callbackAction;
    private final Map<Integer, Callback> callbacks = new HashMap<>();
    private final Map<Integer, Runnable> timeouts = new HashMap<>();
    private boolean receiverRegistered = false;

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public int exitCode = -1;
        public String stdout = "";
        public String stderr = "";
        public String errorMessage = "";
        public boolean timedOut = false;
    }

    public TermuxCommandBridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.callbackAction = appContext.getPackageName() + ".TERMUX_RESULT";
        registerReceiver();
    }

    public boolean isTermuxInstalled() {
        try {
            appContext.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isRunCommandServiceAvailable() {
        try {
            Intent i = new Intent(ACTION_TERMUX_RUN);
            i.setComponent(new ComponentName(TERMUX_PACKAGE, TERMUX_RUN_SERVICE));
            List<ResolveInfo> matches = appContext.getPackageManager().queryIntentServices(i, 0);
            return matches != null && !matches.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    public void runTestCommand(Callback callback) {
        runCommand(
            "/data/data/com.termux/files/usr/bin/sh",
            new String[]{"-lc", "echo agent1c-termux-ok && uname -a"},
            null,
            callback
        );
    }

    public void runCommand(String path, String[] args, String workDir, Callback callback) {
        if (callback == null) return;
        if (!isTermuxInstalled()) {
            Result r = new Result();
            r.errorMessage = "Termux not installed";
            callback.onResult(r);
            return;
        }
        if (!isRunCommandServiceAvailable()) {
            Result r = new Result();
            r.errorMessage = "RunCommandService unavailable";
            callback.onResult(r);
            return;
        }
        final int reqId = nextReqId.incrementAndGet();
        callbacks.put(reqId, callback);

        Intent callbackIntent = new Intent(callbackAction);
        callbackIntent.setPackage(appContext.getPackageName());
        callbackIntent.putExtra(EXTRA_REQ_ID, reqId);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            piFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(appContext, reqId, callbackIntent, piFlags);

        Intent intent = new Intent(ACTION_TERMUX_RUN);
        intent.setComponent(new ComponentName(TERMUX_PACKAGE, TERMUX_RUN_SERVICE));
        intent.putExtra(EXTRA_PATH, path);
        intent.putExtra(EXTRA_ARGS, args == null ? new String[0] : args);
        intent.putExtra(EXTRA_BG, true);
        intent.putExtra(EXTRA_SESSION_ACTION, "0");
        intent.putExtra(EXTRA_STDIN, "");
        if (workDir != null && !workDir.trim().isEmpty()) intent.putExtra(EXTRA_WORKDIR, workDir);
        intent.putExtra(EXTRA_PENDING_INTENT, pendingIntent);

        Runnable timeout = () -> {
            Callback cb = callbacks.remove(reqId);
            timeouts.remove(reqId);
            if (cb == null) return;
            Result r = new Result();
            r.timedOut = true;
            r.errorMessage = "Timed out waiting for Termux result";
            cb.onResult(r);
        };
        timeouts.put(reqId, timeout);
        mainHandler.postDelayed(timeout, 15000L);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Exception e) {
            mainHandler.removeCallbacks(timeout);
            timeouts.remove(reqId);
            callbacks.remove(reqId);
            Result r = new Result();
            r.errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            callback.onResult(r);
        }
    }

    public void shutdown() {
        if (!receiverRegistered) return;
        try {
            appContext.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
        for (Runnable r : new ArrayList<>(timeouts.values())) {
            mainHandler.removeCallbacks(r);
        }
        timeouts.clear();
        callbacks.clear();
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(callbackAction);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            int reqId = intent.getIntExtra(EXTRA_REQ_ID, -1);
            if (reqId < 0) return;

            Runnable timeout = timeouts.remove(reqId);
            if (timeout != null) mainHandler.removeCallbacks(timeout);
            Callback cb = callbacks.remove(reqId);
            if (cb == null) return;

            Result result = new Result();
            Bundle pluginBundle = intent.getBundleExtra(RESULT_BUNDLE_KEY);
            Bundle src = pluginBundle != null ? pluginBundle : intent.getExtras();
            if (src != null) {
                result.stdout = str(src, RESULT_STDOUT);
                result.stderr = str(src, RESULT_STDERR);
                result.errorMessage = str(src, RESULT_ERRMSG);
                if (result.errorMessage.isEmpty()) result.errorMessage = str(src, RESULT_ERR);
                if (src.containsKey(RESULT_EXIT_CODE)) {
                    try {
                        result.exitCode = src.getInt(RESULT_EXIT_CODE, result.exitCode);
                    } catch (Exception ignored) {
                    }
                }
            }
            cb.onResult(result);
        }
    };

    private static String str(Bundle b, String key) {
        try {
            Object v = b.get(key);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception ignored) {
            return "";
        }
    }
}
