package ai.agent1c.hitomi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class SupabaseAuthManager {
    private static final String TAG = "HitomiAuth";
    public static final String SUPABASE_URL = "https://gkfhxhrleuauhnuewfmw.supabase.co";
    public static final String SUPABASE_ANON_KEY = "sb_publishable_r_NH0OEY5Y6rNy9rzPu1NQ_PYGZs5Nj";
    public static final String XAI_CHAT_FUNCTION_URL = SUPABASE_URL + "/functions/v1/xai-chat";
    public static final String APP_REDIRECT_URI = "agent1cai://auth/callback";
    public static final String OAUTH_REDIRECT_URI = "agent1cai://auth/oauth";

    private static final String PREFS = "agent1c_android_auth";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EXPIRES_AT = "expires_at";
    private static final String K_EMAIL = "user_email";
    private static final String K_PROVIDER = "provider";
    private static final String K_DISPLAY = "display_name";
    private static final String K_OAUTH_CODE_VERIFIER = "oauth_code_verifier";

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    public SupabaseAuthManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String buildOAuthUrl(String provider) {
        String codeVerifier = preparePkceCodeVerifier();
        String codeChallenge = pkceCodeChallenge(codeVerifier);
        Uri.Builder b = Uri.parse(SUPABASE_URL + "/auth/v1/authorize").buildUpon();
        b.appendQueryParameter("provider", provider);
        b.appendQueryParameter("redirect_to", OAUTH_REDIRECT_URI);
        b.appendQueryParameter("flow_type", "pkce");
        b.appendQueryParameter("response_type", "code");
        b.appendQueryParameter("code_challenge", codeChallenge);
        b.appendQueryParameter("code_challenge_method", "s256");
        String url = b.build().toString();
        Log.d(TAG, "buildOAuthUrl provider=" + provider + " url=" + url);
        return url;
    }

    public String preparePkceCodeVerifier() {
        String codeVerifier = randomToken(48);
        prefs.edit().putString(K_OAUTH_CODE_VERIFIER, codeVerifier).apply();
        return codeVerifier;
    }

    public static String pkceCodeChallenge(String verifier) {
        return sha256Base64Url(verifier);
    }

    public void completePkceCodeExchange(String code) throws Exception {
        JSONObject exchanged = exchangePkceCode(code);
        String accessToken = exchanged.optString("access_token", "");
        String refreshToken = exchanged.optString("refresh_token", "");
        long expiresIn = exchanged.optLong("expires_in", 3600L);
        if (accessToken.isEmpty()) throw new IllegalStateException("PKCE code exchange returned no access token");
        long expiresAt = (System.currentTimeMillis() / 1000L) + expiresIn;
        prefs.edit()
            .putString(K_ACCESS, accessToken)
            .putString(K_REFRESH, refreshToken)
            .putLong(K_EXPIRES_AT, expiresAt)
            .remove(K_OAUTH_CODE_VERIFIER)
            .apply();
        refreshUserProfile();
    }

    public void sendMagicLink(String email) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("create_user", true);
        body.put("email_redirect_to", APP_REDIRECT_URI);
        body.put("redirect_to", APP_REDIRECT_URI);
        JSONObject options = new JSONObject();
        options.put("email_redirect_to", APP_REDIRECT_URI);
        options.put("redirect_to", APP_REDIRECT_URI);
        body.put("options", options);
        requestJson("POST", SUPABASE_URL + "/auth/v1/otp", body.toString(), null);
    }

    public boolean handleAuthCallbackUri(Uri uri) throws Exception {
        if (uri == null) return false;
        String dataString = uri.toString();
        Log.d(TAG, "handleAuthCallbackUri data=" + dataString);
        if (!dataString.startsWith(APP_REDIRECT_URI)) return false;
        String query = uri.getEncodedQuery();
        String fragment = "";
        int hash = dataString.indexOf('#');
        if (hash >= 0 && hash + 1 < dataString.length()) fragment = dataString.substring(hash + 1);
        JSONObject values = parseQueryLike(fragment);
        if (values.length() == 0 && query != null) values = parseQueryLike(query);
        String callbackState = values.optString("state", "");
        if (!callbackState.isEmpty()) {
            Log.d(TAG, "handleAuthCallbackUri callback state present (Supabase-managed)");
        }

        String accessToken = values.optString("access_token", "");
        String refreshToken = values.optString("refresh_token", "");
        if (accessToken.isEmpty()) {
            String code = values.optString("code", "");
            String err = values.optString("error_description", values.optString("error", ""));
            if (!code.isEmpty()) {
                Log.d(TAG, "handleAuthCallbackUri using PKCE code exchange");
                completePkceCodeExchange(code);
                return true;
            }
            if (!err.isEmpty()) throw new IllegalStateException(err);
            return false;
        }

        long expiresIn = safeLong(values.optString("expires_in", "3600"), 3600L);
        long expiresAt = (System.currentTimeMillis() / 1000L) + expiresIn;
        prefs.edit()
            .putString(K_ACCESS, accessToken)
            .putString(K_REFRESH, refreshToken)
            .putLong(K_EXPIRES_AT, expiresAt)
            .remove(K_OAUTH_CODE_VERIFIER)
            .apply();
        refreshUserProfile();
        Log.d(TAG, "handleAuthCallbackUri implicit/token callback handled");
        return true;
    }

    public boolean isSignedIn() {
        return !getAccessTokenRaw().isEmpty() && !isExpired();
    }

    public String getAccessTokenRaw() {
        return prefs.getString(K_ACCESS, "");
    }

    public String getDisplayName() {
        String display = prefs.getString(K_DISPLAY, "").trim();
        if (!display.isEmpty()) return display;
        String email = getEmail();
        if (email.contains("@")) return email.substring(0, email.indexOf('@'));
        return "friend";
    }

    public String getEmail() {
        return prefs.getString(K_EMAIL, "");
    }

    public String getProvider() {
        return prefs.getString(K_PROVIDER, "");
    }

    public synchronized String ensureValidAccessToken() throws Exception {
        String token = getAccessTokenRaw();
        if (token.isEmpty()) return "";
        long expiresAt = prefs.getLong(K_EXPIRES_AT, 0L);
        long now = System.currentTimeMillis() / 1000L;
        if (expiresAt > now + 120) return token;
        String refresh = prefs.getString(K_REFRESH, "");
        if (refresh.isEmpty()) return token;

        JSONObject body = new JSONObject();
        body.put("refresh_token", refresh);
        JSONObject json = requestJson("POST", SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token", body.toString(), null);
        String newAccess = json.optString("access_token", token);
        String newRefresh = json.optString("refresh_token", refresh);
        long expiresIn = json.optLong("expires_in", 3600L);
        prefs.edit()
            .putString(K_ACCESS, newAccess)
            .putString(K_REFRESH, newRefresh)
            .putLong(K_EXPIRES_AT, (System.currentTimeMillis() / 1000L) + expiresIn)
            .apply();
        try { refreshUserProfile(); } catch (Exception ignored) {}
        return newAccess;
    }

    public void signOut() {
        prefs.edit()
            .remove(K_ACCESS)
            .remove(K_REFRESH)
            .remove(K_EXPIRES_AT)
            .remove(K_EMAIL)
            .remove(K_PROVIDER)
            .remove(K_DISPLAY)
            .apply();
    }

    public void refreshUserProfile() throws Exception {
        String token = getAccessTokenRaw();
        if (token.isEmpty()) return;
        JSONObject user = requestJson("GET", SUPABASE_URL + "/auth/v1/user", null, token);
        String email = user.optString("email", "");
        String provider = "";
        String display = "";

        JSONObject appMeta = user.optJSONObject("app_metadata");
        if (appMeta != null) provider = appMeta.optString("provider", "");

        JSONArray identities = user.optJSONArray("identities");
        if (identities != null) {
            for (int i = 0; i < identities.length(); i++) {
                JSONObject ident = identities.optJSONObject(i);
                if (ident == null) continue;
                String p = ident.optString("provider", "");
                JSONObject idData = ident.optJSONObject("identity_data");
                if (idData == null) continue;
                if ("x".equals(p) || "twitter".equals(p)) {
                    String uname = idData.optString("user_name", "").trim();
                    if (!uname.isEmpty()) display = "@" + uname;
                } else if ("google".equals(p) && display.isEmpty()) {
                    display = idData.optString("email", "");
                }
            }
        }
        if (display.isEmpty() && email.contains("@")) display = email.substring(0, email.indexOf('@'));

        prefs.edit()
            .putString(K_EMAIL, email)
            .putString(K_PROVIDER, provider)
            .putString(K_DISPLAY, display)
            .apply();
    }

    private boolean isExpired() {
        long expiresAt = prefs.getLong(K_EXPIRES_AT, 0L);
        if (expiresAt <= 0L) return false;
        return (System.currentTimeMillis() / 1000L) >= expiresAt;
    }

    private JSONObject parseQueryLike(String raw) throws Exception {
        JSONObject out = new JSONObject();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split("&");
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            String[] kv = part.split("=", 2);
            String k = Uri.decode(kv[0]);
            String v = kv.length > 1 ? Uri.decode(kv[1]) : "";
            out.put(k, v);
        }
        return out;
    }

    private JSONObject requestJson(String method, String url, String jsonBody, String bearerToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("apikey", SUPABASE_ANON_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        if (jsonBody != null) {
            conn.setDoOutput(true);
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(stream);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Supabase auth failed (" + code + "): " + body);
        }
        return body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    private JSONObject exchangePkceCode(String code) throws Exception {
        String verifier = prefs.getString(K_OAUTH_CODE_VERIFIER, "");
        if (verifier.isEmpty()) throw new IllegalStateException("Missing PKCE code verifier");
        Log.d(TAG, "exchangePkceCode start");
        JSONObject body = new JSONObject();
        body.put("auth_code", code);
        body.put("code_verifier", verifier);
        return requestJson("POST", SUPABASE_URL + "/auth/v1/token?grant_type=pkce", body.toString(), null);
    }

    private String randomToken(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String sha256Base64Url(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static long safeLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
