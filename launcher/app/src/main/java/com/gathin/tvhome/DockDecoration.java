package com.gathin.tvhome;

import android.graphics.Rect;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Consistent row spacing, with no extra dock surface or per-frame decoration draws. */
public class DockDecoration extends RecyclerView.ItemDecoration {
    private final int gap;

    DockDecoration(int columns, float density) {
        gap = Math.round(4 * density);
    }

    @Override
    public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        out.top = gap;
        out.bottom = gap;
    }
}
