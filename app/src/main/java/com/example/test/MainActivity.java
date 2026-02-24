package ai.agent1c.hitomi;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private TextView statusText;
    private TextView authStatusText;
    private SupabaseAuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        authManager = new SupabaseAuthManager(this);
        statusText = findViewById(R.id.statusText);
        authStatusText = findViewById(R.id.authStatusText);
        EditText emailInput = findViewById(R.id.emailInput);
        Button googleSignInButton = findViewById(R.id.googleSignInButton);
        Button xSignInButton = findViewById(R.id.xSignInButton);
        Button sendMagicLinkButton = findViewById(R.id.sendMagicLinkButton);
        Button signOutButton = findViewById(R.id.signOutButton);
        Button overlayPermissionButton = findViewById(R.id.overlayPermissionButton);
        Button startOverlayButton = findViewById(R.id.startOverlayButton);
        Button stopOverlayButton = findViewById(R.id.stopOverlayButton);

        handleAuthIntent(getIntent());

        googleSignInButton.setOnClickListener(v -> openWebAuth("google"));
        xSignInButton.setOnClickListener(v -> openWebAuth("x"));
        sendMagicLinkButton.setOnClickListener(v -> {
            String email = emailInput.getText().toString().trim();
            if (email.isEmpty()) {
                authStatusText.setText("Auth: enter an email for Magic Link");
                return;
            }
            authStatusText.setText("Auth: sending magic link...");
            new Thread(() -> {
                try {
                    authManager.sendMagicLink(email);
                    runOnUiThread(() -> authStatusText.setText("Auth: magic link sent. Open email on this phone."));
                } catch (Exception e) {
                    runOnUiThread(() -> authStatusText.setText("Auth error: " + safeMessage(e)));
                }
            }).start();
        });
        signOutButton.setOnClickListener(v -> {
            authManager.signOut();
            refreshAuthStatus();
            statusText.setText("Status: signed out");
        });

        overlayPermissionButton.setOnClickListener(v -> requestOverlayPermission());
        startOverlayButton.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                statusText.setText("Status: overlay permission required");
                requestOverlayPermission();
                return;
            }
            if (!authManager.isSignedIn()) {
                statusText.setText("Status: sign in first");
                authStatusText.setText("Auth: sign in required before chatting");
                return;
            }
            Intent intent = new Intent(this, HedgehogOverlayService.class);
            intent.setAction(HedgehogOverlayService.ACTION_START);
            ContextCompat.startForegroundService(this, intent);
            statusText.setText("Status: starting overlay...");
        });
        stopOverlayButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, HedgehogOverlayService.class);
            intent.setAction(HedgehogOverlayService.ACTION_STOP);
            startService(intent);
            statusText.setText("Status: stopping overlay...");
        });

        refreshAuthStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean allowed = Settings.canDrawOverlays(this);
        statusText.setText(allowed ? "Status: overlay permission granted" : "Status: overlay permission not granted");
        refreshAuthStatus();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        }
    }

    private void openWebAuth(String provider) {
        try {
            String url = authManager.buildWebAuthLaunchUrl(provider);
            if (!launchAuthInBrowser(Uri.parse(url), provider)) {
                throw new IllegalStateException("No browser found for sign-in");
            }
            authStatusText.setText("Auth: waiting for " + provider + " sign-in...");
        } catch (Exception e) {
            authStatusText.setText("Auth error: " + safeMessage(e));
        }
    }

    private boolean launchAuthInBrowser(Uri uri, String provider) {
        try {
            String browserPkg = pickBrowserPackageForOAuth(uri, provider);
            if (browserPkg == null) return false;
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setPackage(browserPkg);
            startActivity(intent);
            if ("x".equals(provider)) {
                authStatusText.setText("Auth: waiting for x sign-in... If X app home opens, use Google/Magic Link or disable 'Open supported links' for X.");
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String pickBrowserPackageForOAuth(Uri uri, String provider) {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW, uri);
        probe.addCategory(Intent.CATEGORY_BROWSABLE);
        List<ResolveInfo> resolved = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null || resolved.isEmpty()) return null;

        List<String> blockedPkgs = Arrays.asList(
            "com.twitter.android",
            "com.x.android"
        );
        List<String> preferredPkgs;
        if ("x".equals(provider)) {
            // Chrome frequently hands x.com links off to the X app; prefer browsers that are less aggressive first.
            preferredPkgs = Arrays.asList(
                "org.mozilla.firefox",
                "org.mozilla.fenix",
                "com.brave.browser",
                "com.microsoft.emmx",
                "com.opera.browser",
                "com.opera.gx",
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev"
            );
        } else {
            preferredPkgs = Arrays.asList(
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev",
                "org.mozilla.firefox",
                "org.mozilla.fenix",
                "com.microsoft.emmx",
                "com.brave.browser",
                "com.opera.browser",
                "com.opera.gx"
            );
        }

        List<String> browserPkgs = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.isEmpty()) continue;
            if (blockedPkgs.contains(pkg)) continue;
            if (pkg.equals(getPackageName())) continue;
            if (!browserPkgs.contains(pkg)) browserPkgs.add(pkg);
        }
        if (browserPkgs.isEmpty()) return null;

        for (String preferred : preferredPkgs) {
            if (browserPkgs.contains(preferred)) return preferred;
        }
        return browserPkgs.get(0);
    }

    private void handleAuthIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        authStatusText.setText("Auth: processing sign-in...");
        new Thread(() -> {
            try {
                boolean handled = authManager.handleAuthCallbackUri(data);
                runOnUiThread(() -> {
                    if (handled) {
                        refreshAuthStatus();
                        Toast.makeText(this, "Signed in. You can start Hitomi now.", Toast.LENGTH_SHORT).show();
                    } else {
                        authStatusText.setText("Auth: callback received but no token found");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> authStatusText.setText("Auth error: " + safeMessage(e)));
            }
        }).start();
    }

    private void refreshAuthStatus() {
        if (!authManager.isSignedIn()) {
            authStatusText.setText("Auth: signed out");
            return;
        }
        String provider = authManager.getProvider();
        String display = authManager.getDisplayName();
        String email = authManager.getEmail();
        StringBuilder sb = new StringBuilder("Auth: signed in");
        if (!provider.isEmpty()) sb.append(" via ").append(provider);
        if (!display.isEmpty()) sb.append(" as ").append(display);
        if (!email.isEmpty() && (display.isEmpty() || !display.equals(email))) sb.append(" (").append(email).append(")");
        authStatusText.setText(sb.toString());
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        return m;
    }
}
