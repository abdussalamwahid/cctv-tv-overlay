package com.salam.tvwidget;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Overlay 2x2 CCTV di home launcher com.gathin.tvhome.
 * v7: ukuran tiap sel = ukuran tile kartu dock (diukur live dari view launcher),
 * posisi kanan atas tepat di atas baris dock, dan ikut scroll (y mengikuti top baris dock).
 * Bunyi tick saat kursor pindah tile.
 */
public class FocusService extends AccessibilityService {

    private static final String HOME_PKG = "com.gathin.tvhome";
    private static final String FRAME_URL = "http://192.168.1.16:8090/frame/cam%d.jpg";
    private static final int GAP = 8;

    private WindowManager wm;
    private View overlay;
    private LinearLayout rootView;
    private GridLayout grid;
    private final ImageView[] cells = new ImageView[4];
    private Handler ui;
    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private SoundPool sp;
    private int tickId = -1;
    private long lastTick = 0;
    private boolean overlayShown = false;

    // geometri terakhir
    private int gCardW = -1, gTileH = -1, gX = 0, gY = 0, gW = 0, gH = 0;
    private int refRowTop = -1; // posisi row dock saat di home (terbesar)
    private float targetAlpha = 1f;
    private float curAlpha = 1f;

    @Override
    protected void onServiceConnected() {
        ui = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        sp = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        tickId = sp.load(this, com.salam.tvwidget.R.raw.tick, 1);
        buildOverlay();
        ui.postDelayed(this::refresh, 500);
        ui.postDelayed(this::poll, 600);
        ui.postDelayed(this::alphaAnim, 40);
    }

    /** Animasi alpha overlay menuju targetAlpha (fade saat scroll). */
    private void alphaAnim() {
        try {
            if (overlay != null && overlayShown) {
                float d = targetAlpha - curAlpha;
                if (Math.abs(d) > 0.02f) {
                    curAlpha += d * 0.25f;
                    overlay.setAlpha(curAlpha);
                } else if (curAlpha != targetAlpha) {
                    curAlpha = targetAlpha;
                    overlay.setAlpha(curAlpha);
                }
            }
        } catch (Exception ignored) {}
        ui.postDelayed(this::alphaAnim, 40);
    }

    private void poll() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence pkg = root != null ? root.getPackageName() : null;
            if (root != null && HOME_PKG.contentEquals(pkg)) {
                updateGeometry(root);
            }
            if (root != null) root.recycle();
            showOverlay(pkg != null && HOME_PKG.contentEquals(pkg));
        } catch (Exception e) { Log.e("CCTV", "poll err", e); }
        ui.postDelayed(this::poll, 400);
    }

    /** Ukur tile dock (4 kartu pertama di RecyclerView launcher) lalu posisikan overlay. */
    private void updateGeometry(AccessibilityNodeInfo root) {
        try {
            AccessibilityNodeInfo rv = findRecycler(root);
            if (rv == null) { root.recycle(); return; }
            int n = rv.getChildCount();
            int take = Math.min(4, n);
            if (take == 0) { rv.recycle(); root.recycle(); return; }
            int rowTop = Integer.MAX_VALUE, rowRight = 0, sumW = 0;
            Rect first = new Rect();
            AccessibilityNodeInfo c0 = rv.getChild(0);
            if (c0 != null) { c0.getBoundsInScreen(first); }
            for (int i = 0; i < take; i++) {
                AccessibilityNodeInfo c = rv.getChild(i);
                if (c == null) continue;
                Rect r = new Rect();
                c.getBoundsInScreen(r);
                if (i < take) { rowTop = Math.min(rowTop, r.top); rowRight = Math.max(rowRight, r.right); sumW += r.width(); }
                c.recycle();
            }
            // tile = anak pertama dari item yang ImageView / view besar (bukan label teks)
            int tileH = -1, cardW = sumW / take;
            if (c0 != null) {
                int cn = c0.getChildCount();
                Rect best = null;
                for (int i = 0; i < cn; i++) {
                    AccessibilityNodeInfo ch = c0.getChild(i);
                    if (ch == null) continue;
                    Rect r = new Rect();
                    ch.getBoundsInScreen(r);
                    CharSequence cls = ch.getClassName();
                    if (cls != null && cls.toString().contains("ImageView")) { best = r; ch.recycle(); break; }
                    if (best == null || r.width() * r.height() > best.width() * best.height()) best = r;
                    ch.recycle();
                }
                if (best != null) tileH = best.height();
                c0.recycle();
            }
            if (tileH <= 0) tileH = (int) ((first.height()) * 0.78f);
            rv.recycle();

            // batas bawah tombol Wi-Fi/Settings (topButtons) — grid tidak boleh menutupnya
            int buttonsBottom = 233;
            try {
                java.util.List<AccessibilityNodeInfo> bl = root.findAccessibilityNodeInfosByViewId("com.gathin.tvhome:id/topButtons");
                if (bl != null && !bl.isEmpty()) {
                    Rect r = new Rect();
                    bl.get(0).getBoundsInScreen(r);
                    buttonsBottom = r.bottom;
                    bl.get(0).recycle();
                }
            } catch (Exception ignored) {}

            int w = 2 * cardW + 3 * GAP;
            int h = 2 * tileH + 3 * GAP;
            // skala tetap dihitung dari posisi home (refRowTop): grid mengecil proporsional
            // agar tidak menutup tombol Wi-Fi/Settings, lalu saat scroll ukurannya tetap,
            // hanya posisi y yang mengikuti baris dock
            if (rowTop > refRowTop) refRowTop = rowTop;
            int maxTop = buttonsBottom + 10;
            int maxH = (refRowTop > 0 ? refRowTop : rowTop) - 10 - maxTop;
            if (maxH > 40 && h > maxH) {
                float scale = maxH / (float) h;
                cardW = Math.round(cardW * scale);
                tileH = Math.round(tileH * scale);
                w = 2 * cardW + 3 * GAP;
                h = 2 * tileH + 3 * GAP;
            }
            int x = rowRight - w;          // rata kanan dengan kartu terakhir (agak ke kanan)
            int y = rowTop - h - 10;       // tepat di atas baris dock; ikut scroll saat rowTop berubah
            // fade: saat scroll turun (rowTop mengecil) perlahan menghilang, kembali saat naik
            float frac = refRowTop > 0 ? (refRowTop - rowTop) / 150f : 0f;
            if (frac < 0f) frac = 0f; if (frac > 1f) frac = 1f;
            targetAlpha = 1f - frac;
            if (w <= 0 || h <= 0) { root.recycle(); return; }

            boolean sizeChanged = (cardW != gCardW || tileH != gTileH);
            gCardW = cardW; gTileH = tileH; gW = w; gH = h; gX = x; gY = y;
            applyGeometry(sizeChanged);
        } catch (Exception e) { Log.e("CCTV", "geom err", e); }
        finally {
            try { root.recycle(); } catch (Exception ignored) {}
        }
    }

    /** Cari GridView launcher (scrollable) — di dalamnya baris pertama = 4 kartu dock. */
    private AccessibilityNodeInfo findRecycler(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("GridView") && node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            AccessibilityNodeInfo r = findRecycler(c);
            if (r != null) { if (c != null && c != r) c.recycle(); return r; }
            if (c != null) c.recycle();
        }
        return null;
    }

    private void applyGeometry(boolean sizeChanged) {
        try {
            if (sizeChanged) {
                for (int i = 0; i < 4; i++) {
                    GridLayout.LayoutParams lp = (GridLayout.LayoutParams) cells[i].getLayoutParams();
                    lp.width = gCardW;
                    lp.height = gTileH;
                    lp.setMargins(GAP / 2, GAP / 2, GAP / 2, GAP / 2);
                    cells[i].setLayoutParams(lp);
                }
                grid.setPadding(GAP, GAP, GAP, GAP);
                grid.setLayoutParams(new LinearLayout.LayoutParams(gW, gH));
            }
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) overlay.getLayoutParams();
            p.width = gW; p.height = gH; p.x = gX; p.y = gY;
            if (overlay.getParent() != null) wm.updateViewLayout(overlay, p);
        } catch (Exception e) { Log.e("CCTV", "apply err", e); }
    }

    private void buildOverlay() {
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setRowCount(2);
        grid.setPadding(GAP, GAP, GAP, GAP);
        for (int i = 0; i < 4; i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            setRounded(iv, 28);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 260;
            lp.height = 150;
            lp.setMargins(GAP / 2, GAP / 2, GAP / 2, GAP / 2);
            iv.setLayoutParams(lp);
            cells[i] = iv;
            grid.addView(iv);
        }
        rootView.addView(grid, new LinearLayout.LayoutParams(2 * 260 + 3 * GAP, 2 * 150 + 3 * GAP));
        overlay = rootView;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                2 * 260 + 3 * GAP, 2 * 150 + 3 * GAP,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = 660;
        p.y = 96;
        overlay.setLayoutParams(p);
        gW = p.width; gH = p.height; gX = p.x; gY = p.y;
        gCardW = 260; gTileH = 150;
    }

    /** Outline provider untuk sudut rounded. */
    private static class RoundedOutline extends ViewOutlineProvider {
        private final float radius;
        RoundedOutline(float radius) { this.radius = radius; }
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }

    /** Sudut sel grid dibuat rounded seperti kartu aplikasi (Netflix dll). */
    private void setRounded(final ImageView iv, final float radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF11151C);
        bg.setCornerRadius(radius);
        iv.setBackground(bg);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new RoundedOutline(radius));
    }

    private void showOverlay(boolean show) {
        if (show == overlayShown || overlay == null) return;
        overlayShown = show;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlay.getLayoutParams();
            if (lp.width != gW) { lp.width = gW; lp.height = gH; lp.x = gX; lp.y = gY; }
            if (show) {
                if (overlay.getParent() == null) wm.addView(overlay, lp);
            } else if (overlay.getParent() != null) {
                wm.removeView(overlay);
            }
        } catch (Exception e) { Log.e("CCTV", "add/remove err", e); }
    }

    private void refresh() {
        final long ts = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            net.execute(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(String.format(FRAME_URL, idx + 1) + "?t=" + ts).openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    c.setRequestProperty("Cache-Control", "no-cache");
                    InputStream in = c.getInputStream();
                    final Bitmap bmp = BitmapFactory.decodeStream(in);
                    in.close();
                    if (bmp != null) ui.post(() -> { if (cells[idx] != null) cells[idx].setImageBitmap(bmp); });
                } catch (Exception ignored) {}
            });
        }
        ui.postDelayed(this::refresh, 1000);
    }

    private void playTick() {
        long now = System.currentTimeMillis();
        if (now - lastTick < 60) return;
        lastTick = now;
        if (sp != null && tickId > 0) sp.play(tickId, 0.5f, 0.5f, 1, 0, 1.0f);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || ui == null) return;
        CharSequence pkg = event.getPackageName();
        int type = event.getEventType();
        if (pkg == null) return;
        if ("com.salam.tvwidget".contentEquals(pkg)) return; // abaikan event dari overlay sendiri
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            final boolean home = HOME_PKG.contentEquals(pkg);
            ui.post(() -> showOverlay(home));
        } else if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            if (HOME_PKG.contentEquals(pkg)) playTick();
        } else if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (HOME_PKG.contentEquals(pkg)) {
                ui.post(() -> {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        try {
                            if (HOME_PKG.contentEquals(root.getPackageName())) updateGeometry(root);
                            else root.recycle();
                        } catch (Exception ignored) {}
                    }
                });
            }
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (ui != null) ui.post(() -> showOverlay(false));
        if (sp != null) sp.release();
        super.onDestroy();
    }
}
