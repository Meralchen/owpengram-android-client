package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Story-style segmented progress at the top of the Reels viewer. */
public final class ReelsStoryProgress extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float gap;
    private final float strokeHeight;
    private final float horizontalPadding;
    private int count = 1;
    private int selected;
    private float progress;

    public ReelsStoryProgress(Context context) {
        super(context);
        gap = dp(3);
        strokeHeight = dp(2.5f);
        horizontalPadding = dp(8);
        paint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    public void setState(int count, int selected, float progress) {
        this.count = Math.max(1, count);
        this.selected = Math.max(0, Math.min(selected, this.count - 1));
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    public void setSelected(int selected) {
        this.selected = Math.max(0, Math.min(selected, count - 1));
        progress = 0f;
        invalidate();
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    public void setCount(int count) {
        this.count = Math.max(1, count);
        selected = Math.min(selected, this.count - 1);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float available = Math.max(0, getWidth() - horizontalPadding * 2);
        final float totalGap = gap * Math.max(0, count - 1);
        final float segmentWidth = Math.max(0, (available - totalGap) / count);
        final float top = dp(4);
        final float bottom = top + strokeHeight;
        for (int i = 0; i < count; i++) {
            final float left = horizontalPadding + i * (segmentWidth + gap);
            rect.set(left, top, left + segmentWidth, bottom);
            paint.setColor(i < selected ? 0xFFFFFFFF : 0x55FFFFFF);
            canvas.drawRoundRect(rect, strokeHeight / 2f, strokeHeight / 2f, paint);
            if (i == selected) {
                paint.setColor(0xFFFFFFFF);
                rect.right = left + segmentWidth * progress;
                canvas.drawRoundRect(rect, strokeHeight / 2f, strokeHeight / 2f, paint);
            }
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
