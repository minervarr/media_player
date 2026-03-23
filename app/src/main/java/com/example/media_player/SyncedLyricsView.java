package com.example.media_player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import androidx.core.content.res.ResourcesCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import java.util.List;

public class SyncedLyricsView extends View {

    private static final int COLOR_ACTIVE = 0xFF00E676;
    private static final int COLOR_INACTIVE = 0xFF757575;
    private static final float ACTIVE_SP = 20f;
    private static final float INACTIVE_SP = 16f;
    private static final float MIN_ALPHA = 0.4f;
    private static final float MAX_ALPHA = 0.6f;

    private final TextPaint activePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint inactivePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float lineSpacing;
    private final float horizontalPadding;

    private LrcParser.LrcResult lrcResult;
    private List<LrcParser.LrcLine> lines;
    private float[] lineHeights;
    private float[] lineYOffsets;
    private float totalContentHeight;

    private StaticLayout[] activeLayouts;
    private StaticLayout[] inactiveLayouts;

    private int activeIndex = -1;
    private float scrollY = 0;
    private ValueAnimator scrollAnimator;

    private OverScroller scroller;
    private GestureDetector gestureDetector;
    private float manualScrollY = 0;

    private boolean needsMeasure = false;

    public SyncedLyricsView(Context context) {
        this(context, null);
    }

    public SyncedLyricsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SyncedLyricsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, getResources().getDisplayMetrics());
        float dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());

        activePaint.setColor(COLOR_ACTIVE);
        activePaint.setTextSize(ACTIVE_SP * sp);
        activePaint.setTextAlign(Paint.Align.LEFT);

        inactivePaint.setColor(COLOR_INACTIVE);
        inactivePaint.setTextSize(INACTIVE_SP * sp);
        inactivePaint.setTextAlign(Paint.Align.LEFT);

        lineSpacing = 12 * dp;
        horizontalPadding = lineSpacing * 2;

        Typeface boldSerif;
        try {
            Typeface serifFont = ResourcesCompat.getFont(context, R.font.cmu_serif);
            boldSerif = Typeface.create(serifFont, Typeface.BOLD);
        } catch (Exception e) {
            boldSerif = Typeface.create("serif", Typeface.BOLD);
        }
        activePaint.setTypeface(boldSerif);
        inactivePaint.setTypeface(boldSerif);


        scroller = new OverScroller(context);
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (lrcResult != null && !lrcResult.isSynced) {
                    manualScrollY = Math.max(0, Math.min(manualScrollY + distanceY,
                            Math.max(0, totalContentHeight - getHeight())));
                    invalidate();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (lrcResult != null && !lrcResult.isSynced) {
                    scroller.fling(0, (int) manualScrollY, 0, (int) -velocityY,
                            0, 0, 0, (int) Math.max(0, totalContentHeight - getHeight()));
                    invalidate();
                    return true;
                }
                return false;
            }
        });
    }

    public void setLyrics(LrcParser.LrcResult result) {
        this.lrcResult = result;
        this.activeIndex = -1;
        this.scrollY = 0;
        this.manualScrollY = 0;

        if (result == null || result.lines.isEmpty()) {
            this.lines = null;
            this.lineHeights = null;
            this.lineYOffsets = null;
            this.activeLayouts = null;
            this.inactiveLayouts = null;
            this.totalContentHeight = 0;
            invalidate();
            return;
        }

        this.lines = result.lines;
        if (getWidth() > 0) {
            preMeasureLines();
        } else {
            needsMeasure = true;
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw && lines != null) {
            preMeasureLines();
            invalidate();
        } else if (needsMeasure && lines != null && w > 0) {
            needsMeasure = false;
            preMeasureLines();
            invalidate();
        }
    }

    private void preMeasureLines() {
        if (lines == null) return;
        int count = lines.size();
        int availableWidth = (int) (getWidth() - horizontalPadding);
        if (availableWidth <= 0) return;

        lineHeights = new float[count];
        lineYOffsets = new float[count];
        activeLayouts = new StaticLayout[count];
        inactiveLayouts = new StaticLayout[count];

        float y = 0;
        for (int i = 0; i < count; i++) {
            String text = lines.get(i).text;

            activeLayouts[i] = StaticLayout.Builder.obtain(text, 0, text.length(), activePaint, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build();

            inactiveLayouts[i] = StaticLayout.Builder.obtain(text, 0, text.length(), inactivePaint, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build();

            float h = activeLayouts[i].getHeight() + lineSpacing;
            lineHeights[i] = h;
            lineYOffsets[i] = y;
            y += h;

        }
        totalContentHeight = y;
    }

    public void updatePosition(long positionMs) {
        if (lrcResult == null || !lrcResult.isSynced || lines == null || lines.isEmpty()) return;

        int newIndex = findActiveIndex(positionMs);

        if (newIndex == activeIndex) return;
        activeIndex = newIndex;

        float targetY = 0;
        if (activeIndex >= 0 && activeIndex < lineYOffsets.length) {
            targetY = lineYOffsets[activeIndex] + lineHeights[activeIndex] / 2f;
        }

        float fromY = scrollY;
        float toY = targetY;

        if (scrollAnimator != null && scrollAnimator.isRunning()) {
            scrollAnimator.cancel();
        }
        scrollAnimator = ValueAnimator.ofFloat(fromY, toY);
        scrollAnimator.setDuration(200);
        scrollAnimator.setInterpolator(new FastOutSlowInInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            scrollY = (float) animation.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.start();
    }

    public boolean hasLyrics() {
        return lines != null && !lines.isEmpty();
    }

    private int findActiveIndex(long positionMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int lo = 0, hi = lines.size() - 1, result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timestampMs <= positionMs) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (lrcResult != null && !lrcResult.isSynced) {
            gestureDetector.onTouchEvent(event);
            return true;
        }
        return false;
    }

    @Override
    public void computeScroll() {
        if (lrcResult != null && !lrcResult.isSynced && scroller.computeScrollOffset()) {
            manualScrollY = scroller.getCurrY();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (lines == null || lines.isEmpty() || activeLayouts == null) return;

        float centerY = getHeight() / 2f;

        if (lrcResult != null && lrcResult.isSynced) {
            drawSyncedLyrics(canvas, centerY);
        } else {
            drawUnsyncedLyrics(canvas);
        }
    }

    private void drawSyncedLyrics(Canvas canvas, float centerY) {
        float padLeft = horizontalPadding / 2f;

        for (int i = 0; i < lines.size(); i++) {
            float lineTop = lineYOffsets[i];
            float drawY = centerY + (lineTop - scrollY);

            // Skip lines far off screen
            float totalLineSpace = lineHeights[i];
            if (drawY + totalLineSpace < 0 || drawY > getHeight() + lineHeights[i]) continue;

            boolean isActive = (i == activeIndex);
            StaticLayout layout;
            TextPaint paint;
            if (isActive) {
                layout = activeLayouts[i];
                paint = activePaint;
                paint.setAlpha(255);
            } else {
                layout = inactiveLayouts[i];
                paint = inactivePaint;
                float distance = Math.abs(i - (activeIndex >= 0 ? activeIndex : 0));
                float alpha = Math.max(MIN_ALPHA, MAX_ALPHA - distance * 0.05f);
                paint.setAlpha((int) (alpha * 255));
            }

            canvas.save();
            canvas.translate(padLeft, drawY);
            layout.draw(canvas);
            canvas.restore();

        }
    }

    private void drawUnsyncedLyrics(Canvas canvas) {
        float padLeft = horizontalPadding / 2f;
        float paddingTop = lineSpacing * 2;

        for (int i = 0; i < lines.size(); i++) {
            float drawY = paddingTop + lineYOffsets[i] - manualScrollY;

            if (drawY + lineHeights[i] < 0 || drawY > getHeight() + lineHeights[i]) continue;

            inactivePaint.setAlpha((int) (MAX_ALPHA * 255));

            canvas.save();
            canvas.translate(padLeft, drawY);
            inactiveLayouts[i].draw(canvas);
            canvas.restore();
        }
    }
}
