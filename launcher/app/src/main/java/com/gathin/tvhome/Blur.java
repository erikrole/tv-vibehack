package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Cheap software blur for the backdrop. RenderEffect needs Android 12; the TV runs 11. The art is drawn tiny
 * (96x54), box-blurred and scaled up with filtering, once per focus change and cached per app.
 */
final class Blur {
    private static final int W = 96, H = 54;

    private Blur() {}

    /** Draws {@code d} cover-fitted into a small bitmap and blurs it. Returns null when there is nothing to draw. */
    static Bitmap of(Drawable live) {
        if (live == null) return null;
        // Private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int iw = Math.max(1, d.getIntrinsicWidth()), ih = Math.max(1, d.getIntrinsicHeight());
        float scale = Math.max(W / (float) iw, H / (float) ih);
        int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF101012);
        d.setBounds((W - dw) / 2, (H - dh) / 2, (W - dw) / 2 + dw, (H - dh) / 2 + dh);
        d.draw(c);
        d.setBounds(saved);
        int[] px = new int[W * H];
        bmp.getPixels(px, 0, W, 0, 0, W, H);
        for (int i = 0; i < 3; i++) {
            px = pass(px, true, 5);
            px = pass(px, false, 5);
        }
        bmp.setPixels(px, 0, W, 0, 0, W, H);
        return bmp;
    }

    private static int[] pass(int[] src, boolean horizontal, int r) {
        int[] out = new int[src.length];
        int len = horizontal ? W : H, lines = horizontal ? H : W;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.min(len - 1, Math.max(0, i + k));
                    int p = horizontal ? src[l * W + j] : src[j * W + l];
                    a += p >>> 24;
                    rr += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                    n++;
                }
                int v = (a / n) << 24 | (rr / n) << 16 | (g / n) << 8 | (b / n);
                if (horizontal) out[l * W + i] = v;
                else out[i * W + l] = v;
            }
        }
        return out;
    }
}
