package ai.agent1c.hitomi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HedgehogOverlayService extends Service {
    public static final String ACTION_START = "ai.agent1c.hitomi.START_OVERLAY";
    public static final String ACTION_STOP = "ai.agent1c.hitomi.STOP_OVERLAY";
    private static final String CHANNEL_ID = "hitomi_overlay_channel";
    private static final int NOTIF_ID = 1017;
    private static final int HEDGEHOG_SIZE_DP = 112;
    private static final int HEDGEHOG_TOUCH_BOX_DP = 124;
    private static final int BUBBLE_WIDTH_DP = 260;
    private static final int BUBBLE_X_OFFSET_DP = 74;
    private static final int BUBBLE_Y_OFFSET_DP = 170;

    private WindowManager windowManager;
    private View hedgehogView;
    private View bubbleView;
    private WindowManager.LayoutParams hedgehogParams;
    private WindowManager.LayoutParams bubbleParams;
    private boolean bubbleVisible = false;
    private final ExecutorService chatExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final JSONArray chatHistory = new JSONArray();
    private HitomiCloudChatClient chatClient;
    private TextView bubbleBodyView;
    private EditText bubbleInputView;
    private ImageButton bubbleSendButton;
    private boolean chatInFlight = false;
    private String transcript = "";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        ensureOverlay();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        chatExecutor.shutdownNow();
        if (windowManager != null) {
            if (hedgehogView != null) {
                try { windowManager.removeView(hedgehogView); } catch (Exception ignored) {}
            }
            if (bubbleView != null) {
                try { windowManager.removeView(bubbleView); } catch (Exception ignored) {}
            }
        }
    }

    private void ensureOverlay() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (hedgehogView != null) return;

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        hedgehogView = LayoutInflater.from(this).inflate(R.layout.overlay_hedgehog, null);
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);

        hedgehogParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        hedgehogParams.gravity = Gravity.TOP | Gravity.START;
        hedgehogParams.x = dp(18);
        hedgehogParams.y = dp(220);

        bubbleParams = new WindowManager.LayoutParams(
            dp(BUBBLE_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dp(82);
        bubbleParams.y = dp(80);

        setupBubbleUi();
        setupDragAndTap();

        windowManager.addView(bubbleView, bubbleParams);
        windowManager.addView(hedgehogView, hedgehogParams);
        bubbleView.setVisibility(View.GONE);
        chatClient = new HitomiCloudChatClient(this);
        transcript = "Hitomi: Hi! I'm Hitomi, your tiny hedgehog friend. Sign in in the app, then we can chat here.";
        renderTranscript(false);
    }

    private void setupBubbleUi() {
        bubbleBodyView = bubbleView.findViewById(R.id.hitomiBubbleBody);
        bubbleInputView = bubbleView.findViewById(R.id.hitomiBubbleInput);
        bubbleSendButton = bubbleView.findViewById(R.id.hitomiBubbleSend);
        ImageButton close = bubbleView.findViewById(R.id.hitomiBubbleClose);
        bubbleSendButton.setOnClickListener(v -> sendChatMessage());
        close.setOnClickListener(v -> toggleBubble(false));
    }

    private void setupDragAndTap() {
        final float[] downRaw = new float[2];
        final int[] downPos = new int[2];
        final boolean[] dragging = new boolean[1];

        hedgehogView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging[0] = false;
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    downPos[0] = hedgehogParams.x;
                    downPos[1] = hedgehogParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downRaw[0]);
                    int dy = (int) (event.getRawY() - downRaw[1]);
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragging[0] = true;
                    hedgehogParams.x = clamp(downPos[0] + dx, 0, Math.max(0, getScreenWidth() - dp(HEDGEHOG_TOUCH_BOX_DP)));
                    hedgehogParams.y = clamp(downPos[1] + dy, 0, Math.max(0, getScreenHeight() - dp(HEDGEHOG_TOUCH_BOX_DP)));
                    positionBubbleNearHedgehog();
                    safeUpdate(hedgehogView, hedgehogParams);
                    if (bubbleVisible) safeUpdate(bubbleView, bubbleParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging[0]) toggleBubble(!bubbleVisible);
                    return true;
                default:
                    return false;
            }
        });
    }

    private void toggleBubble(boolean show) {
        bubbleVisible = show;
        if (bubbleView == null) return;
        if (show) {
            positionBubbleNearHedgehog();
            safeUpdate(bubbleView, bubbleParams);
            bubbleView.setVisibility(View.VISIBLE);
            if (bubbleInputView != null) bubbleInputView.requestFocus();
        } else {
            bubbleView.setVisibility(View.GONE);
        }
    }

    private void positionBubbleNearHedgehog() {
        int bubbleWidth = dp(BUBBLE_WIDTH_DP);
        int bubbleX = hedgehogParams.x - dp(BUBBLE_X_OFFSET_DP);
        int bubbleY = hedgehogParams.y - dp(BUBBLE_Y_OFFSET_DP);
        bubbleParams.x = clamp(bubbleX, 0, Math.max(0, getScreenWidth() - bubbleWidth));
        bubbleParams.y = clamp(bubbleY, 0, Math.max(0, getScreenHeight() - dp(240)));
    }

    private void safeUpdate(View view, WindowManager.LayoutParams lp) {
        if (windowManager == null || view == null) return;
        try {
            if (view.getWindowToken() != null) windowManager.updateViewLayout(view, lp);
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, openIntent, flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hitomi overlay running")
            .setContentText("Tap to manage the floating hedgehog.")
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Hitomi Overlay",
            NotificationManager.IMPORTANCE_LOW
        );
        nm.createNotificationChannel(channel);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getScreenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int getScreenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void sendChatMessage() {
        if (chatInFlight || bubbleInputView == null) return;
        String msg = bubbleInputView.getText().toString().trim();
        if (msg.isEmpty()) return;
        bubbleInputView.setText("");
        appendTranscriptLine("You: " + msg);
        chatInFlight = true;
        renderTranscript(true);
        if (bubbleSendButton != null) bubbleSendButton.setEnabled(false);

        chatExecutor.execute(() -> {
            String reply;
            try {
                SupabaseAuthManager auth = new SupabaseAuthManager(this);
                String userName = auth.getDisplayName();
                chatHistory.put(new JSONObject().put("role", "user").put("content", msg));
                reply = chatClient.send(chatHistory, userName);
                chatHistory.put(new JSONObject().put("role", "assistant").put("content", reply));
            } catch (Exception e) {
                reply = "I hit a snag: " + safeMessage(e);
            }
            final String finalReply = reply;
            mainHandler.post(() -> {
                appendTranscriptLine("Hitomi: " + finalReply);
                chatInFlight = false;
                renderTranscript(false);
                if (bubbleSendButton != null) bubbleSendButton.setEnabled(true);
            });
        });
    }

    private void appendTranscriptLine(String line) {
        if (transcript == null || transcript.isEmpty()) transcript = line;
        else transcript = transcript + "\n\n" + line;
    }

    private void renderTranscript(boolean thinking) {
        if (bubbleBodyView == null) return;
        String text = transcript == null ? "" : transcript;
        if (thinking) text = text + "\n\nThinking...";
        bubbleBodyView.setText(text);
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        return m;
    }
}
