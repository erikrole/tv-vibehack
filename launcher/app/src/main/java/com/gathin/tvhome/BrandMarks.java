package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** Local vector wordmarks for common apps, with a bounded installed-icon fallback. No network or banners. */
final class BrandMarks {
    private BrandMarks() {}

    static Drawable load(Context context, String pkg, ResolveInfo info, PackageManager pm) {
        int resource;
        switch (pkg) {
            case "com.google.android.youtube.tv": resource = R.drawable.brand_youtube; break;
            case "com.google.android.youtube.tvunplugged": resource = R.drawable.brand_youtubetv; break;
            case "com.apple.atve.androidtv.appletv": resource = R.drawable.brand_appletv; break;
            case "com.netflix.ninja": resource = R.drawable.brand_netflix; break;
            case "com.hulu.livingroomplus": resource = R.drawable.brand_hulu; break;
            case "com.amazon.amazonvideo.livingroom": resource = R.drawable.brand_primevideo; break;
            case "com.bamnetworks.mobile.android.gameday.atbat": resource = R.drawable.brand_mlb; break;
            case "com.android.vending": resource = R.drawable.brand_googleplay; break;
            case "com.android.tv.settings": resource = R.drawable.ic_settings; break;
            default: resource = 0;
        }
        if (resource != 0) return context.getDrawable(resource);
        Drawable icon = info.loadIcon(pm);
        if (icon == null) return pm.getDefaultActivityIcon();
        int size = Math.min(256, Math.max(1, Math.round(72 * context.getResources().getDisplayMetrics().density)));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Rect bounds = new Rect(icon.getBounds());
        try {
            icon.setBounds(0, 0, size, size);
            icon.draw(new Canvas(bitmap));
        } finally {
            icon.setBounds(bounds);
        }
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
