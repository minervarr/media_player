package com.example.media_player;

import com.matrixplayer.audioengine.SignalPathInfo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

public class SignalPathView extends View {

    private static final int COLOR_NODE_FILL = 0xFF000000;
    private static final int COLOR_NODE_BORDER = 0xFF1B5E20;
    private static final int COLOR_GREEN_BRIGHT = 0xFF00E676;
    private static final int COLOR_TEXT_PRIMARY = 0xFFE0E0E0;
    private static final int COLOR_TEXT_SECONDARY = 0xFF757575;
    private static final int COLOR_LABEL = 0xFF1B5E20;

    private final Paint nodeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint connectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint qualityBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint primaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float dp;
    private final float cornerRadius;
    private final float qualityBarWidth;
    private final float connectorWidth;
    private final float nodePaddingH;
    private final float nodePaddingV;
    private final float nodeMarginH;
    private final float connectorGap;
    private final float labelHeight;
    private final float lineSpacing;

    private SignalPathInfo info;
    private int mode;

    public SignalPathView(Context context) {
        this(context, null);
    }

    public SignalPathView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SignalPathView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, getResources().getDisplayMetrics());

        cornerRadius = 6 * dp;
        qualityBarWidth = 3 * dp;
        connectorWidth = 1.5f * dp;
        nodePaddingH = 10 * dp;
        nodePaddingV = 8 * dp;
        nodeMarginH = 12 * dp;
        connectorGap = 4 * dp;
        lineSpacing = 2 * dp;

        Typeface mono = Typeface.MONOSPACE;

        nodeFillPaint.setColor(COLOR_NODE_FILL);
        nodeFillPaint.setStyle(Paint.Style.FILL);

        nodeBorderPaint.setColor(COLOR_NODE_BORDER);
        nodeBorderPaint.setStyle(Paint.Style.STROKE);
        nodeBorderPaint.setStrokeWidth(1 * dp);

        connectorPaint.setColor(COLOR_NODE_BORDER);
        connectorPaint.setStyle(Paint.Style.STROKE);
        connectorPaint.setStrokeWidth(connectorWidth);

        qualityBarPaint.setStyle(Paint.Style.FILL);

        labelPaint.setTypeface(mono);
        labelPaint.setTextSize(9 * sp);
        labelPaint.setColor(COLOR_LABEL);

        primaryPaint.setTypeface(mono);
        primaryPaint.setTextSize(11 * sp);
        primaryPaint.setColor(COLOR_TEXT_PRIMARY);

        secondaryPaint.setTypeface(mono);
        secondaryPaint.setTextSize(10 * sp);
        secondaryPaint.setColor(COLOR_TEXT_SECONDARY);

        badgePaint.setTypeface(Typeface.create(mono, Typeface.BOLD));
        badgePaint.setTextSize(9 * sp);
        badgePaint.setColor(COLOR_GREEN_BRIGHT);

        labelHeight = labelPaint.getTextSize() + 4 * dp;
    }

    public void setInfo(SignalPathInfo info, int mode) {
        this.info = info;
        this.mode = mode;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);

        if (info == null || mode <= 0) {
            setMeasuredDimension(width, 0);
            return;
        }

        float totalHeight = 0;

        if (mode == 1) {
            // Util: SOURCE + OUTPUT
            totalHeight += labelHeight + measureNodeHeight(getSourceLines(false));
            totalHeight += connectorGap * 2 + 12 * dp; // connector
            totalHeight += labelHeight + measureNodeHeight(getOutputLines(false));
        } else {
            // Verbose: SOURCE + DECODE + [EQ] + OUTPUT
            totalHeight += labelHeight + measureNodeHeight(getSourceLines(true));
            totalHeight += connectorGap * 2 + 12 * dp;
            totalHeight += labelHeight + measureNodeHeight(getDecodeLines());
            if (info.eqActive) {
                totalHeight += connectorGap * 2 + 12 * dp;
                totalHeight += labelHeight + measureNodeHeight(getEqLines());
            }
            totalHeight += connectorGap * 2 + 12 * dp;
            totalHeight += labelHeight + measureNodeHeight(getOutputLines(true));
        }

        totalHeight += 8 * dp; // bottom padding

        setMeasuredDimension(width, (int) Math.ceil(totalHeight));
    }

    private float measureNodeHeight(String[] lines) {
        float h = nodePaddingV * 2;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) h += lineSpacing;
            h += (i == 0 ? primaryPaint : secondaryPaint).getTextSize();
        }
        return h;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (info == null || mode <= 0) return;

        float left = nodeMarginH;
        float right = getWidth() - nodeMarginH;
        float y = 0;

        if (mode == 1) {
            y = drawNode(canvas, "SOURCE", getSourceLines(false), info.getSourceQualityColor(), left, right, y, false);
            y = drawConnector(canvas, left, right, y);
            y = drawNode(canvas, "OUTPUT", getOutputLines(false), info.getOutputQualityColor(), left, right, y, info.isBitPerfect);
        } else {
            y = drawNode(canvas, "SOURCE", getSourceLines(true), info.getSourceQualityColor(), left, right, y, false);
            y = drawConnector(canvas, left, right, y);
            y = drawNode(canvas, "DECODE", getDecodeLines(), info.getDecodeQualityColor(), left, right, y, false);
            if (info.eqActive) {
                y = drawConnector(canvas, left, right, y);
                y = drawNode(canvas, "EQ", getEqLines(), 0xFFFFB300, left, right, y, false);
            }
            y = drawConnector(canvas, left, right, y);
            y = drawNode(canvas, "OUTPUT", getOutputLines(true), info.getOutputQualityColor(), left, right, y, info.isBitPerfect);
        }
    }

    private float drawNode(Canvas canvas, String label, String[] lines, int qualityColor,
                           float left, float right, float y, boolean showBadge) {
        // Label
        canvas.drawText(label, left + qualityBarWidth + 6 * dp, y + labelPaint.getTextSize(), labelPaint);
        y += labelHeight;

        float nodeHeight = measureNodeHeight(lines);
        RectF rect = new RectF(left, y, right, y + nodeHeight);

        // Fill + border
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, nodeFillPaint);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, nodeBorderPaint);

        // Quality bar
        qualityBarPaint.setColor(qualityColor);
        float barTop = y + cornerRadius;
        float barBottom = y + nodeHeight - cornerRadius;
        canvas.drawRect(left + 1 * dp, barTop, left + 1 * dp + qualityBarWidth, barBottom, qualityBarPaint);

        // Text lines
        float textX = left + qualityBarWidth + nodePaddingH;
        float textY = y + nodePaddingV;

        for (int i = 0; i < lines.length; i++) {
            Paint paint = (i == 0) ? primaryPaint : secondaryPaint;
            textY += paint.getTextSize();
            canvas.drawText(lines[i], textX, textY, paint);
            if (i < lines.length - 1) textY += lineSpacing;
        }

        // Bit-perfect badge
        if (showBadge) {
            String badge = "[BIT-PERFECT]";
            float badgeWidth = badgePaint.measureText(badge);
            canvas.drawText(badge, right - nodePaddingH - badgeWidth, y + nodePaddingV + badgePaint.getTextSize(), badgePaint);
        }

        return y + nodeHeight;
    }

    private float drawConnector(Canvas canvas, float left, float right, float y) {
        float centerX = (left + right) / 2f;
        y += connectorGap;
        float lineEnd = y + 12 * dp;
        canvas.drawLine(centerX, y, centerX, lineEnd, connectorPaint);
        return lineEnd + connectorGap;
    }

    private String[] getSourceLines(boolean verbose) {
        if (info == null) return new String[]{"--"};

        StringBuilder line1 = new StringBuilder();
        if ("TIDAL".equals(info.sourceType) && info.tidalQuality != null) {
            line1.append("TIDAL ");
        }
        line1.append(info.sourceFormat != null ? info.sourceFormat : "PCM");
        line1.append("  ");

        if (info.isDsd) {
            line1.append(info.sourceChannels).append("ch");
        } else {
            String rateStr = formatRate(info.sourceRate);
            line1.append(rateStr).append("/").append(info.sourceBitDepth).append("bit/").append(info.sourceChannels).append("ch");
        }

        if (!verbose) {
            return new String[]{line1.toString()};
        }

        // Verbose: add second line
        StringBuilder line2 = new StringBuilder();
        if ("TIDAL".equals(info.sourceType)) {
            if (info.tidalQuality != null) line2.append(info.tidalQuality);
            if (info.tidalRequestedQuality != null
                    && !info.tidalRequestedQuality.equals(info.tidalQuality)) {
                line2.append(" (req: ").append(info.tidalRequestedQuality).append(")");
            }
            if (info.tidalCodec != null) {
                if (line2.length() > 0) line2.append("  |  ");
                line2.append(info.tidalCodec);
            }
            if (info.tidalFileSize > 0) {
                if (line2.length() > 0) line2.append("  |  ");
                line2.append(formatFileSize(info.tidalFileSize));
            }
        } else {
            if (info.sourceMime != null) line2.append(info.sourceMime);
            line2.append("  |  LOCAL");
        }

        return new String[]{line1.toString(), line2.toString()};
    }

    private String[] getDecodeLines() {
        if (info == null) return new String[]{"--"};

        java.util.List<String> lines = new java.util.ArrayList<>();

        lines.add(info.codecName != null ? info.codecName : "unknown");

        if (info.isDsd && "Native".equals(info.dsdPlaybackMode)) {
            lines.add("DSD Native (raw bitstream)");
            lines.add(dsdRateLabel(info.dsdRate) + "  " + info.sourceChannels + "ch");
        } else {
            lines.add("Output: " + info.getDecodedEncodingName());
            if (info.isDsd) {
                String dsdLabel = dsdRateLabel(info.dsdRate);
                lines.add("DSD>PCM: " + dsdLabel + " -> " + info.dsdPcmRate + "Hz/32bit");
            }
        }

        return lines.toArray(new String[0]);
    }

    private String[] getEqLines() {
        if (info == null || !info.eqActive) return new String[]{"--"};
        return new String[]{
                info.eqProfileName != null ? info.eqProfileName : "Unknown",
                "Parametric EQ (10-band biquad)"
        };
    }

    private String[] getOutputLines(boolean verbose) {
        if (info == null) return new String[]{"--"};

        java.util.List<String> lines = new java.util.ArrayList<>();

        if (!verbose) {
            // Compact: single line with device + rate/bits
            StringBuilder sb = new StringBuilder();
            sb.append(info.outputDevice != null ? info.outputDevice : "Speaker");
            sb.append("  ");
            if (info.isDsd && "Native".equals(info.dsdPlaybackMode)) {
                sb.append(dsdRateLabel(info.dsdRate)).append(" Native");
            } else {
                sb.append(formatRate(info.outputRate)).append("/").append(info.outputBitDepth).append("bit");
            }
            lines.add(sb.toString());
        } else {
            // Verbose: multi-line
            lines.add(info.outputDevice != null ? info.outputDevice : "Speaker");
            if (info.isDsd && "Native".equals(info.dsdPlaybackMode)) {
                lines.add(dsdRateLabel(info.dsdRate) + " Native/" + info.outputChannels + "ch");
            } else {
                lines.add(formatRate(info.outputRate) + "/" + info.outputBitDepth + "bit/" + info.outputChannels + "ch");
            }

            if (info.writePathLabel != null) {
                lines.add("Write: " + info.writePathLabel);
            }

            if (info.usbSupportedRates != null && info.usbSupportedRates.length > 0) {
                StringBuilder rates = new StringBuilder("Rates: ");
                for (int i = 0; i < info.usbSupportedRates.length; i++) {
                    if (i > 0) rates.append("/");
                    float kHz = info.usbSupportedRates[i] / 1000f;
                    if (kHz == (int) kHz) {
                        rates.append((int) kHz);
                    } else {
                        rates.append(String.format("%.1f", kHz));
                    }
                }
                lines.add(rates.toString());
            }
        }

        return lines.toArray(new String[0]);
    }

    private static String formatRate(int rate) {
        if (rate <= 0) return "?kHz";
        if (rate % 1000 == 0) {
            return (rate / 1000) + "kHz";
        }
        return String.format("%.1fkHz", rate / 1000.0);
    }

    private static String dsdRateLabel(int rate) {
        if (rate == 2822400) return "DSD64";
        if (rate == 5644800) return "DSD128";
        if (rate == 11289600) return "DSD256";
        return "DSD";
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
