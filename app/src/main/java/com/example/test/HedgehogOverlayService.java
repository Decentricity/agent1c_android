package ai.agent1c.hitomi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.animation.ValueAnimator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
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
    private static final int BUBBLE_GAP_DP = 8;

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
    private ScrollView bubbleBodyScrollView;
    private View bubbleTailTopView;
    private View bubbleTailBottomView;
    private EditText bubbleInputView;
    private ImageButton bubbleSendButton;
    private boolean chatInFlight = false;
    private String transcript = "";
    private boolean keyboardLiftActive = false;
    private int keyboardLiftOriginalY = -1;
    private ValueAnimator hedgehogHopAnimator;
    private boolean bubbleTailOnTop = false;
    private static volatile boolean overlayRunning = false;

    public static boolean isOverlayRunning() {
        return overlayRunning;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            overlayRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        ensureOverlay();
        overlayRunning = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        overlayRunning = false;
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
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
        SupabaseAuthManager auth = new SupabaseAuthManager(this);
        if (auth.isSignedIn()) {
            String display = String.valueOf(auth.getDisplayName() == null ? "" : auth.getDisplayName()).trim();
            if (display.startsWith("@") && display.length() > 1) {
                transcript = "Hitomi: I'm a hedgey-hog! Hello " + display;
            } else {
                transcript = "Hitomi: Hello, I'm a hedgey-hog!";
            }
        } else {
            transcript = "Hitomi: Hi! I'm Hitomi, your tiny hedgehog friend. Sign in in the app, then we can chat here.";
        }
        renderTranscript(false);
    }

    private void setupBubbleUi() {
        bubbleBodyScrollView = bubbleView.findViewById(R.id.hitomiBubbleBodyScroll);
        bubbleBodyView = bubbleView.findViewById(R.id.hitomiBubbleBody);
        bubbleTailTopView = bubbleView.findViewById(R.id.hitomiBubbleTailTop);
        bubbleTailBottomView = bubbleView.findViewById(R.id.hitomiBubbleTailBottom);
        bubbleInputView = bubbleView.findViewById(R.id.hitomiBubbleInput);
        bubbleSendButton = bubbleView.findViewById(R.id.hitomiBubbleSend);
        ImageButton close = bubbleView.findViewById(R.id.hitomiBubbleClose);
        bubbleSendButton.setOnClickListener(v -> sendChatMessage());
        close.setOnClickListener(v -> toggleBubble(false));
        bubbleView.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                toggleBubble(false);
                return false;
            }
            return false;
        });
        bubbleInputView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                scheduleKeyboardAvoidanceHop();
            } else {
                restoreFromKeyboardHop(true);
            }
        });
        bubbleInputView.setOnClickListener(v -> scheduleKeyboardAvoidanceHop());
        bubbleView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!bubbleVisible || bubbleInputView == null || !bubbleInputView.hasFocus()) return;
            int screenH = getScreenHeight();
            int keyboardTop = getKeyboardTop(screenH);
            if (keyboardTop < screenH - dp(40)) {
                ensureKeyboardAvoidanceHop();
            }
        });
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
                    if (keyboardLiftActive) {
                        keyboardLiftOriginalY = downPos[1] + dy;
                    }
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
            if (bubbleInputView != null) {
                bubbleInputView.requestFocus();
                scheduleKeyboardAvoidanceHop();
            }
            bubbleView.post(this::ensureKeyboardAvoidanceHop);
            bubbleView.postDelayed(this::ensureKeyboardAvoidanceHop, 120);
        } else {
            if (bubbleInputView != null) bubbleInputView.clearFocus();
            bubbleView.setVisibility(View.GONE);
            restoreFromKeyboardHop(true);
        }
    }

    private void positionBubbleNearHedgehog() {
        int bubbleWidth = dp(BUBBLE_WIDTH_DP);
        int bubbleX = hedgehogParams.x - dp(BUBBLE_X_OFFSET_DP);
        int bubbleHeight = getBubbleMeasuredHeight();
        int hedgehogCenterY = hedgehogParams.y + (dp(HEDGEHOG_TOUCH_BOX_DP) / 2);
        boolean placeBelow = hedgehogCenterY < Math.round(getScreenHeight() * 0.4f);
        int bubbleY = placeBelow
            ? hedgehogParams.y + dp(HEDGEHOG_TOUCH_BOX_DP) + dp(BUBBLE_GAP_DP)
            : hedgehogParams.y - bubbleHeight - dp(BUBBLE_GAP_DP);
        bubbleTailOnTop = placeBelow;
        updateBubbleTailPlacement();
        bubbleParams.x = clamp(bubbleX, 0, Math.max(0, getScreenWidth() - bubbleWidth));
        bubbleParams.y = clamp(bubbleY, 0, Math.max(0, getScreenHeight() - bubbleHeight));
        positionBubbleTailTowardHedgehog();
    }

    private void updateBubbleTailPlacement() {
        if (bubbleTailTopView == null || bubbleTailBottomView == null) return;
        bubbleTailTopView.setVisibility(bubbleTailOnTop ? View.VISIBLE : View.GONE);
        bubbleTailBottomView.setVisibility(bubbleTailOnTop ? View.GONE : View.VISIBLE);
    }

    private void positionBubbleTailTowardHedgehog() {
        View tail = bubbleTailOnTop ? bubbleTailTopView : bubbleTailBottomView;
        if (tail == null) return;
        int bubbleWidth = dp(BUBBLE_WIDTH_DP);
        int tailWidth = tail.getWidth() > 0 ? tail.getWidth() : dp(18);
        int hedgehogHeadCenterX = hedgehogParams.x + (dp(HEDGEHOG_TOUCH_BOX_DP) / 2);
        int targetCenterWithinBubble = hedgehogHeadCenterX - bubbleParams.x;
        int defaultCenter = bubbleWidth / 2;
        int minCenter = dp(16) + (tailWidth / 2);
        int maxCenter = bubbleWidth - dp(16) - (tailWidth / 2);
        int clampedCenter = clamp(targetCenterWithinBubble, minCenter, maxCenter);
        float dx = clampedCenter - defaultCenter;
        tail.setTranslationX(dx);
    }

    private int getBubbleMeasuredHeight() {
        if (bubbleView == null) return dp(220);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(dp(BUBBLE_WIDTH_DP), View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        try {
            bubbleView.measure(widthSpec, heightSpec);
            int h = bubbleView.getMeasuredHeight();
            if (h > 0) return h;
        } catch (Exception ignored) {
        }
        return dp(220);
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
        scheduleKeyboardAvoidanceHop();
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
        if (bubbleBodyScrollView != null) {
            bubbleBodyScrollView.post(() -> bubbleBodyScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void scheduleKeyboardAvoidanceHop() {
        mainHandler.postDelayed(this::ensureKeyboardAvoidanceHop, 80);
        mainHandler.postDelayed(this::ensureKeyboardAvoidanceHop, 220);
    }

    private void ensureKeyboardAvoidanceHop() {
        if (hedgehogView == null || hedgehogParams == null) return;
        int screenH = getScreenHeight();
        int keyboardTopEstimate = getKeyboardTop(screenH);
        int hedgehogBottom = hedgehogParams.y + dp(HEDGEHOG_TOUCH_BOX_DP);
        int overlayBottom = hedgehogBottom;
        if (bubbleVisible && bubbleView != null) {
            int bubbleHeight = getBubbleMeasuredHeight();
            int bubbleBottom = bubbleParams.y + bubbleHeight;
            overlayBottom = Math.max(overlayBottom, bubbleBottom);
        }
        if (overlayBottom <= keyboardTopEstimate) return;
        int liftPx = overlayBottom - keyboardTopEstimate + dp(8);
        if (bubbleVisible && bubbleTailOnTop) {
            liftPx += Math.round(screenH * 0.05f);
        }
        int targetY = clamp(hedgehogParams.y - liftPx, 0, Math.max(0, screenH - dp(HEDGEHOG_TOUCH_BOX_DP)));
        if (!keyboardLiftActive) {
            keyboardLiftOriginalY = hedgehogParams.y;
            keyboardLiftActive = true;
        }
        animateHedgehogHopTo(targetY);
    }

    private int getKeyboardTop(int screenH) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                View insetSource = bubbleView != null ? bubbleView : hedgehogView;
                if (insetSource != null) {
                    WindowInsets insets = insetSource.getRootWindowInsets();
                    if (insets != null) {
                        int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                        if (imeBottom > 0) return screenH - imeBottom;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        try {
            View frameSource = bubbleView != null ? bubbleView : hedgehogView;
            if (frameSource != null) {
                Rect r = new Rect();
                frameSource.getWindowVisibleDisplayFrame(r);
                if (r.bottom > 0 && r.bottom < screenH) {
                    return r.bottom;
                }
            }
        } catch (Exception ignored) {
        }
        return screenH - dp(270);
    }

    private void restoreFromKeyboardHop(boolean animated) {
        if (!keyboardLiftActive) return;
        int targetY = keyboardLiftOriginalY >= 0 ? keyboardLiftOriginalY : hedgehogParams.y;
        keyboardLiftActive = false;
        keyboardLiftOriginalY = -1;
        if (animated) animateHedgehogHopTo(targetY);
        else {
            hedgehogParams.y = targetY;
            positionBubbleNearHedgehog();
            safeUpdate(hedgehogView, hedgehogParams);
            if (bubbleVisible) safeUpdate(bubbleView, bubbleParams);
        }
    }

    private void animateHedgehogHopTo(int targetY) {
        if (hedgehogView == null || hedgehogParams == null) return;
        int startY = hedgehogParams.y;
        if (startY == targetY) return;
        if (hedgehogHopAnimator != null) hedgehogHopAnimator.cancel();
        final int delta = targetY - startY;
        hedgehogHopAnimator = ValueAnimator.ofFloat(0f, 1f);
        hedgehogHopAnimator.setDuration(260L);
        hedgehogHopAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            float arc = (float) Math.sin(Math.PI * t) * dp(22);
            int y = Math.round(startY + (delta * t) - arc);
            hedgehogParams.y = clamp(y, 0, Math.max(0, getScreenHeight() - dp(HEDGEHOG_TOUCH_BOX_DP)));
            positionBubbleNearHedgehog();
            safeUpdate(hedgehogView, hedgehogParams);
            if (bubbleVisible) safeUpdate(bubbleView, bubbleParams);
        });
        hedgehogHopAnimator.start();
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        return m;
    }
}
