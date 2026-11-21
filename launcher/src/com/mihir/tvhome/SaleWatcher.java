package com.mihir.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Polls the Cat3D hub for new MercadoLibre sales and shows a banner over whatever is on screen.
 * Configured over ADB through MainActivity (extras feed_token / feed_url), stored in prefs only.
 * ponytail: 30 s polling, not push; the build has no WebSocket client and 2 requests/min is nothing.
 */
public class SaleWatcher extends Service {
    static final String PREFS = "sales_feed";
    static final long POLL_MS = 30_000;
    static final long BANNER_MS = 15_000;
    private static final String TAG = "SaleWatcher";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread poller;
    private View banner;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, SaleWatcher.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("ventas", "Ventas", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "ventas")
                .setSmallIcon(R.drawable.ic_home).setContentTitle("Avisos de ventas").build());

        if (intent != null && intent.hasExtra("test_title")) {
            String t = intent.getStringExtra("test_title"), b = intent.getStringExtra("test_body");
            main.postDelayed(() -> show(t, b), intent.getIntExtra("test_delay_ms", 0));
        }
        if (poller == null) {
            poller = new Thread(this::loop, "sale-poller");
            poller.start();
        }
        return START_STICKY;
    }

    private void loop() {
        while (true) {
            try {
                poll();
            } catch (Exception e) {
                Log.w(TAG, "poll failed: " + e); // network blips are normal; try again next round
            }
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
        }
    }

    private void poll() throws Exception {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = p.getString("token", "");
        String url = p.getString("url", getString(R.string.sales_feed_url));
        if (token.isEmpty() || url.isEmpty()) return;
        String since = p.getString("since", "");

        HttpURLConnection c = (HttpURLConnection) new URL(
                since.isEmpty() ? url : url + "?since=" + URLEncoder.encode(since, "UTF-8")).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        int code = c.getResponseCode();
        if (code != 200) throw new IllegalStateException("hub answered HTTP " + code + " for " + url);
        JSONObject body = new JSONObject(read(c.getInputStream()));
        c.disconnect();

        JSONArray sales = body.getJSONArray("sales");
        for (int i = 0; i < sales.length(); i++) {
            JSONObject s = sales.getJSONObject(i);
            String title = s.getString("title"), text = s.optString("body");
            main.post(() -> show(title, text));
        }
        // The hub's clock decides "new", so a wrong TV clock can't skip or repeat sales.
        p.edit().putString("since", body.getString("now")).apply();
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    // ---------------------------------------------------------------- banner

    private void show(String title, String text) {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "no overlay permission: run appops set com.mihir.tvhome SYSTEM_ALERT_WINDOW allow");
            return;
        }
        WindowManager wm = getSystemService(WindowManager.class);
        if (banner != null) wm.removeView(banner);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(16), dp(22), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(0xF20D0221);
        bg.setStroke(dp(2), MainActivity.NEON_PINK);
        box.setBackground(bg);
        box.addView(label("// VENTA", 10, MainActivity.NEON_PINK));
        box.addView(label(title, 16, MainActivity.NEON_CYAN));
        if (text != null && !text.isEmpty()) box.addView(label(text, 12, 0xE6FFFFFF));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(460), -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(36);
        lp.y = dp(36);
        box.setAlpha(0f);
        wm.addView(box, lp);
        box.animate().alpha(1f).setDuration(300).start();
        banner = box;
        chime();

        main.postDelayed(() -> {
            if (banner != box) return;
            box.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                if (banner == box) { wm.removeView(box); banner = null; }
            }).start();
        }, BANNER_MS);
    }

    private TextView label(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setShadowLayer(dp(6), 0, 0, color);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    /** Our own sound (assets/sale-chime.wav): TVs often have no default notification sound at all. */
    private void chime() {
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("sale-chime.wav")) {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            // Media stream = the TV's volume, so it's heard over Netflix at whatever level you watch.
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            mp.setOnCompletionListener(android.media.MediaPlayer::release);
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            Log.w(TAG, "no chime: " + e);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
