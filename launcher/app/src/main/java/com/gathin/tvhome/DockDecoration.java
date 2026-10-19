package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastSheenTop = Float.NaN, lastSheenH = Float.NaN;
    private final RectF rect = new RectF();

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        band.setColor(0x2EFFFFFF);   // translucent glass band (Liquid Glass look), the ambient glow shows through
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x4DFFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (36 * density);   // keeps the next row of apps below the screen edge until you press Down
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 16 * density;
        rect.inset(-pad, -pad);
        float radius = 36 * density;   // tile radius (20) + band padding (16): concentric corners
        c.drawRoundRect(rect, radius, radius, band);
        // Specular top highlight: a bright-to-clear gradient over the upper part of the band (Liquid Glass look).
        // The shader is only rebuilt when the band moves: allocating one per frame was wasted work on a slow TV.
        float sheenTop = rect.top, sheenH = rect.height();
        if (sheenTop != lastSheenTop || sheenH != lastSheenH) {
            sheen.setShader(new LinearGradient(0, sheenTop, 0, sheenTop + sheenH * 0.35f,
                    0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            lastSheenTop = sheenTop;
            lastSheenH = sheenH;
        }
        c.drawRoundRect(rect, radius, radius, sheen);
    }
}
