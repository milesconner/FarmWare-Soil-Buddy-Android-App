package farmware.soilbuddyapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MoistureChartView extends View {
    public static class Point {
        public final long timeMillis;
        public final float value;

        public Point(long timeMillis, float value) {
            this.timeMillis = timeMillis;
            this.value = value;
        }
    }

    private static final long DAY = 24L * 60L * 60L * 1000L;
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Point> points = new ArrayList<>();
    private long rangeMillis = DAY;
    private Float fixedMinimum;
    private Float fixedMaximum;
    private String[] categoricalLabels;
    private boolean stepLine;

    public MoistureChartView(Context context) {
        this(context, null);
    }

    public MoistureChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gridPaint.setColor(0xFFE7ECE4);
        gridPaint.setStrokeWidth(dp(1));
        linePaint.setColor(0xFF4F7651);
        linePaint.setStrokeWidth(dp(3));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        pointPaint.setColor(0xFF4F7651);
        textPaint.setColor(0xFF778477);
        textPaint.setTextSize(dp(11));
    }

    public void setPoints(List<Point> points) {
        this.points = new ArrayList<>(points);
        invalidate();
    }

    public void setRangeHours(int hours) {
        rangeMillis = hours * 60L * 60L * 1000L;
        invalidate();
    }

    public void setYAxisBounds(Float minimum, Float maximum) {
        fixedMinimum = minimum;
        fixedMaximum = maximum;
        invalidate();
    }

    public void setLineColor(int color) {
        linePaint.setColor(color);
        pointPaint.setColor(color);
        invalidate();
    }

    public void setCategoricalLabels(String low, String medium, String high) {
        categoricalLabels = new String[]{low, medium, high};
        fixedMinimum = 0f;
        fixedMaximum = 2f;
        stepLine = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(38);
        float right = getWidth() - dp(8);
        float top = dp(12);
        float bottom = getHeight() - dp(29);
        if (right <= left || bottom <= top) return;

        long endTime = System.currentTimeMillis();
        long startTime = endTime - rangeMillis;
        List<Point> visible = new ArrayList<>();
        for (Point point : points) {
            if (point.timeMillis >= startTime && point.timeMillis <= endTime) visible.add(point);
        }

        float minValue = fixedMinimum == null ? Float.POSITIVE_INFINITY : fixedMinimum;
        float maxValue = fixedMaximum == null ? Float.NEGATIVE_INFINITY : fixedMaximum;
        if (fixedMinimum == null || fixedMaximum == null) {
            for (Point point : visible) {
                minValue = Math.min(minValue, point.value);
                maxValue = Math.max(maxValue, point.value);
            }
            if (visible.isEmpty()) {
                minValue = 0;
                maxValue = 100;
            } else if (maxValue == minValue) {
                float pad = Math.max(1, Math.abs(maxValue) * 0.1f);
                minValue -= pad;
                maxValue += pad;
            } else {
                float pad = (maxValue - minValue) * 0.1f;
                minValue -= pad;
                maxValue += pad;
            }
        }
        if (maxValue <= minValue) maxValue = minValue + 1;

        for (int tick = 0; tick <= 2; tick++) {
            float fraction = tick / 2f;
            float y = top + (bottom - top) * fraction;
            canvas.drawLine(left, y, right, y, gridPaint);
            float labelValue = maxValue - (maxValue - minValue) * fraction;
            String label = categoricalLabels == null
                    ? formatValue(labelValue)
                    : categoricalLabels[2 - tick];
            canvas.drawText(label, left - dp(6) - textPaint.measureText(label), y + dp(4), textPaint);
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat(rangeMillis <= DAY ? "h a" : "MMM d", Locale.getDefault());
        canvas.drawText(dateFormat.format(new Date(startTime)), left, getHeight() - dp(5), textPaint);
        String middle = dateFormat.format(new Date(startTime + rangeMillis / 2));
        float middleWidth = textPaint.measureText(middle);
        canvas.drawText(middle, (left + right - middleWidth) / 2f, getHeight() - dp(5), textPaint);
        String end = "Now";
        canvas.drawText(end, right - textPaint.measureText(end), getHeight() - dp(5), textPaint);

        if (visible.isEmpty()) {
            String message = "No readings in this range yet";
            canvas.drawText(message, left + dp(4), (top + bottom) / 2f, textPaint);
            return;
        }

        List<Point> drawPoints = decimate(visible, 700);
        Path path = new Path();
        for (int i = 0; i < drawPoints.size(); i++) {
            Point point = drawPoints.get(i);
            float x = left + (right - left) * (point.timeMillis - startTime) / (float) rangeMillis;
            float value = Math.max(minValue, Math.min(maxValue, point.value));
            float y = bottom - (bottom - top) * (value - minValue) / (maxValue - minValue);
            if (i == 0) {
                path.moveTo(x, y);
            } else if (stepLine) {
                Point previous = drawPoints.get(i - 1);
                float previousValue = Math.max(minValue, Math.min(maxValue, previous.value));
                float previousY = bottom - (bottom - top) * (previousValue - minValue) / (maxValue - minValue);
                path.lineTo(x, previousY);
                path.lineTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, linePaint);

        if (drawPoints.size() == 1) {
            Point point = visible.get(0);
            float x = left + (right - left) * (point.timeMillis - startTime) / (float) rangeMillis;
            float value = Math.max(minValue, Math.min(maxValue, point.value));
            float y = bottom - (bottom - top) * (value - minValue) / (maxValue - minValue);
            canvas.drawCircle(x, y, dp(4), pointPaint);
        }
    }

    private List<Point> decimate(List<Point> source, int maximumPoints) {
        if (source.size() <= maximumPoints) return source;
        List<Point> result = new ArrayList<>(maximumPoints);
        double step = (source.size() - 1d) / (maximumPoints - 1d);
        for (int i = 0; i < maximumPoints; i++) result.add(source.get((int) Math.round(i * step)));
        return result;
    }

    private String formatValue(float value) {
        if (Math.abs(value - Math.round(value)) < 0.05f) return Long.toString(Math.round(value));
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
