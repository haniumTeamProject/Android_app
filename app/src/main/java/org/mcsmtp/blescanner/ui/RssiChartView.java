package org.mcsmtp.blescanner.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import org.mcsmtp.blescanner.data.FilterConfig;
import org.mcsmtp.blescanner.data.RssiPoint;
import org.mcsmtp.blescanner.filter.FilterRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.view.ScaleGestureDetector;

/**
 * RSSI 그래프 (Compose Canvas 버전 이식): 원본 + 필터 겹쳐 그리기,
 * 구간 마킹 세로선/음영, 탭 crosshair + 각 시리즈 값 라벨.
 */
public class                                      RssiChartView extends View {

    public interface OnTapListener {
        void onTap(long timestamp);
    }

    private static final int[] FILTER_COLORS = new int[]{
            Color.parseColor("#F44336"), // 빨강 (이동평균)
            Color.parseColor("#4CAF50"), // 초록
            Color.parseColor("#FF9800"), // 주황
            Color.parseColor("#9C27B0"), // 보라
            Color.parseColor("#00BCD4")  // 청록
    };

    private static final java.util.Map<String, Integer> FILTER_COLOR_MAP = new java.util.HashMap<>();
    static {
        FILTER_COLOR_MAP.put("중앙값", Color.parseColor("#F44336"));       // 빨강
        FILTER_COLOR_MAP.put("칼만 필터", Color.parseColor("#4CAF50"));    // 초록
        FILTER_COLOR_MAP.put("히스테리시스", Color.parseColor("#FF9800")); // 주황
        FILTER_COLOR_MAP.put("종합 필터", Color.parseColor("#9C27B0"));    // 보라
    }
    private static final int ORIGINAL_COLOR = Color.parseColor("#2196F3");
    private static final int BACKGROUND_COLOR = Color.parseColor("#F5F5F5");

    private List<RssiPoint> history = Collections.emptyList();
    private boolean showOriginal = true;
    private List<FilterConfig> activeFilters = Collections.emptyList();
    private List<Series> seriesList = Collections.emptyList();
    private Long markStart;
    private Long markEnd;
    private int rssiStepSize = 20;
    private Long tappedTimestamp;

    private OnTapListener tapListener;

    private boolean zoomEnabled = false;
    private float scaleFactor = 1f;
    private float translateX = 0f;
    private float translateY = 0f;
    private ScaleGestureDetector scaleGestureDetector;
    private float lastPanX, lastPanY;
    private boolean isPanning = false;
    private GestureDetector gestureDetector;

    private final float leftPadding;
    private final float rightPadding;
    private final float topPadding;
    private final float bottomPadding;
    private final float textSizePx;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tapLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tapLabelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Rect textBounds = new Rect();

    public RssiChartView(Context context) {
        this(context, null);
    }

    public RssiChartView(Context context, AttributeSet attrs) {
        super(context, attrs);

        float density = context.getResources().getDisplayMetrics().density;
        leftPadding = 60f * density;
        rightPadding = 16f * density;
        topPadding = 16f * density;
        bottomPadding = 40f * density;
        textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f,
                context.getResources().getDisplayMetrics());

        bgPaint.setColor(BACKGROUND_COLOR);

        gridPaint.setColor(Color.LTGRAY);
        gridPaint.setStrokeWidth(1f); // Compose 원본과 동일하게 원시 픽셀 단위 (density 배율 적용 안 함)

        labelPaint.setColor(Color.DKGRAY);
        labelPaint.setTextSize(textSizePx);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f); // Compose Stroke(width = 4f)와 동일 (원시 픽셀)

        markPaint.setColor(Color.RED);
        markPaint.setStrokeWidth(3f);

        shadePaint.setColor(Color.RED);
        shadePaint.setAlpha(25);

        crosshairPaint.setColor(Color.GRAY);
        crosshairPaint.setStrokeWidth(2f);

        tapLabelTextPaint.setColor(Color.WHITE);
        tapLabelTextPaint.setTextSize(textSizePx);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // 확대된 상태에서는 탭 좌표 계산이 어긋나므로 원래 배율일 때만 크로스헤어 동작
                if (tapListener != null && scaleFactor <= 1.01f) {
                    handleTap(e.getX());
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (zoomEnabled) {
                    resetZoom();
                    return true;
                }
                return false;
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float prevScale = scaleFactor;
                scaleFactor = clamp(scaleFactor * detector.getScaleFactor(), 1f, 5f);
                float factor = scaleFactor / prevScale;
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                // 두 손가락 중심(focus) 아래 콘텐츠가 화면상 같은 위치에 남도록 보정
                translateX = focusX - (focusX - translateX) * factor;
                translateY = focusY - (focusY - translateY) * factor;
                clampTranslation();
                invalidate();
                return true;
            }
        });
    }

    public void setOnTapListener(OnTapListener listener) {
        this.tapListener = listener;
    }
    public void setZoomable(boolean enabled) {
        this.zoomEnabled = enabled;
        if (!enabled) resetZoom();
    }

    public void resetZoom() {
        scaleFactor = 1f;
        translateX = 0f;
        translateY = 0f;
        invalidate();
    }
    public void setTappedTimestamp(Long timestamp) {
        this.tappedTimestamp = timestamp;
        invalidate();
    }

    public void setRssiStepSize(int stepSize) {
        this.rssiStepSize = stepSize;
    }

    public void setData(List<RssiPoint> history, boolean showOriginal, List<FilterConfig> activeFilters,
                         Long markStart, Long markEnd) {
        this.history = history != null ? history : Collections.emptyList();
        this.showOriginal = showOriginal;
        this.activeFilters = activeFilters != null ? activeFilters : Collections.emptyList();
        this.markStart = markStart;
        this.markEnd = markEnd;
        // 이동평균 등 필터 계산은 데이터가 바뀔 때 한 번만 하고, onDraw에서는 매번 다시 계산하지 않는다
        // (스캔 결과가 잦을 때 매 프레임 재계산하면 불필요하게 무거워진다).
        this.seriesList = buildSeriesList();
        invalidate();
    }

    private List<Series> buildSeriesList() {
        List<Series> list = new ArrayList<>();
        if (showOriginal) list.add(new Series("원본", history, ORIGINAL_COLOR));
        for (FilterConfig filter : activeFilters) {
            List<RssiPoint> filtered = FilterRepository.applyFilter(history, filter);
            Integer color = FILTER_COLOR_MAP.get(filter.getName());
            if (color == null) color = FILTER_COLORS[0]; // 혹시 모를 이름 없는 필터 대비
            list.add(new Series(filter.getName(), filtered, color));
        }
        return list;
    }

    public static int getColorForFilterName(String name) {
        Integer color = FILTER_COLOR_MAP.get(name);
        return color != null ? color : FILTER_COLORS[0];
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (zoomEnabled) {
            scaleGestureDetector.onTouchEvent(event);
            handlePan(event);
        }
        if (tapListener != null || zoomEnabled) {
            gestureDetector.onTouchEvent(event);
        }
        return true;
    }

    private void handlePan(MotionEvent event) {
        if (scaleFactor <= 1.01f) return; // 확대 안 된 상태면 팬 필요 없음
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastPanX = event.getX();
                lastPanY = event.getY();
                isPanning = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1) {
                    float dx = event.getX() - lastPanX;
                    float dy = event.getY() - lastPanY;
                    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) isPanning = true;
                    if (isPanning) {
                        translateX += dx;
                        translateY += dy;
                        clampTranslation();
                        lastPanX = event.getX();
                        lastPanY = event.getY();
                        invalidate();
                    }
                }
                break;
        }
    }

    private void clampTranslation() {
        float maxTx = getWidth() * (scaleFactor - 1f);
        float maxTy = getHeight() * (scaleFactor - 1f);
        translateX = clamp(translateX, -maxTx, maxTx);
        translateY = clamp(translateY, -maxTy, maxTy);
    }

    private void handleTap(float touchX) {
        if (history.isEmpty()) return;
        float chartWidth = getWidth() - leftPadding - rightPadding;
        if (chartWidth <= 0) return;
        if (touchX < leftPadding || touchX > getWidth() - rightPadding) return;

        float fraction = clamp((touchX - leftPadding) / chartWidth, 0f, 1f);
        long minT = history.get(0).getTimestamp();
        long maxT = Math.max(history.get(history.size() - 1).getTimestamp(), minT + 1L);
        long ts = minT + (long) (fraction * (maxT - minT));
        tapListener.onTap(ts);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        if (history.isEmpty()) return;

        canvas.save();
        if (zoomEnabled) {
            canvas.translate(translateX, translateY);
            canvas.scale(scaleFactor, scaleFactor);
        }

        float chartWidth = getWidth() - leftPadding - rightPadding;
        float chartHeight = getHeight() - topPadding - bottomPadding;
        if (chartWidth <= 0 || chartHeight <= 0) {
            canvas.restore();
            return;
        }

        float minRssi = -100f;
        float maxRssi = -20f;
        float rssiRange = maxRssi - minRssi;

        long minTime = history.get(0).getTimestamp();
        long maxTime = Math.max(history.get(history.size() - 1).getTimestamp(), minTime + 1000L);
        float timeRange = Math.max(maxTime - minTime, 1f);

        // 가로 격자선 + Y축 라벨
        for (int rssiValue = -100; rssiValue <= -20; rssiValue += rssiStepSize) {
            float y = yFor(rssiValue, minRssi, rssiRange, chartHeight);
            canvas.drawLine(leftPadding, y, getWidth() - rightPadding, y, gridPaint);
            String text = String.valueOf(rssiValue);
            labelPaint.getTextBounds(text, 0, text.length(), textBounds);
            canvas.drawText(text, leftPadding - textBounds.width() - 8f, y + textBounds.height() / 2f, labelPaint);
        }

        // 세로 격자선 + X축 라벨
        int timeStepCount = 5;
        for (int i = 0; i <= timeStepCount; i++) {
            float fraction = i / (float) timeStepCount;
            float x = leftPadding + fraction * chartWidth;
            canvas.drawLine(x, topPadding, x, topPadding + chartHeight, gridPaint);
            long elapsedSec = (long) ((timeRange * fraction) / 1000);
            String text = elapsedSec + "s";
            labelPaint.getTextBounds(text, 0, text.length(), textBounds);
            canvas.drawText(text, x - textBounds.width() / 2f, topPadding + chartHeight + textBounds.height() + 8f, labelPaint);
        }

        for (Series series : seriesList) {
            drawSeries(canvas, series.points, series.color, minTime, timeRange, minRssi, rssiRange, chartWidth, chartHeight);
        }

        // 구간 마커 세로선 + 음영
        if (markStart != null) {
            float x = xFor(markStart, minTime, timeRange, chartWidth);
            canvas.drawLine(x, topPadding, x, topPadding + chartHeight, markPaint);
        }
        if (markEnd != null) {
            float x = xFor(markEnd, minTime, timeRange, chartWidth);
            canvas.drawLine(x, topPadding, x, topPadding + chartHeight, markPaint);
        }
        if (markStart != null && markEnd != null) {
            float x1 = xFor(Math.min(markStart, markEnd), minTime, timeRange, chartWidth);
            float x2 = xFor(Math.max(markStart, markEnd), minTime, timeRange, chartWidth);
            canvas.drawRect(x1, topPadding, x2, topPadding + chartHeight, shadePaint);
        }

        // 탭한 지점 세로선 + 각 시리즈 값 라벨
        if (tappedTimestamp != null) {
            float tapX = xFor(tappedTimestamp, minTime, timeRange, chartWidth);
            canvas.drawLine(tapX, topPadding, tapX, topPadding + chartHeight, crosshairPaint);

            for (int idx = 0; idx < seriesList.size(); idx++) {
                Series series = seriesList.get(idx);
                RssiPoint nearest = findNearest(series.points, tappedTimestamp);
                if (nearest == null) continue;

                float py = yFor(nearest.getRssi(), minRssi, rssiRange, chartHeight);
                dotPaint.setColor(series.color);
                canvas.drawCircle(tapX, py, 5f, dotPaint);

                String labelText = series.label + ": " + nearest.getRssi();
                tapLabelTextPaint.getTextBounds(labelText, 0, labelText.length(), textBounds);
                float labelHeight = textBounds.height() + 6f;
                float labelY = topPadding + idx * (labelHeight + 4f);
                float labelX = Math.min(tapX + 8f, getWidth() - textBounds.width() - 4f);

                tapLabelBgPaint.setColor(series.color);
                canvas.drawRect(labelX - 2f, labelY - 1f, labelX + textBounds.width() + 4f, labelY + labelHeight, tapLabelBgPaint);
                canvas.drawText(labelText, labelX, labelY + textBounds.height(), tapLabelTextPaint);
            }
        }

        canvas.restore();
    }

    private RssiPoint findNearest(List<RssiPoint> points, long timestamp) {
        RssiPoint nearest = null;
        long bestDiff = Long.MAX_VALUE;
        for (RssiPoint p : points) {
            long diff = Math.abs(p.getTimestamp() - timestamp);
            if (diff < bestDiff) {
                bestDiff = diff;
                nearest = p;
            }
        }
        return nearest;
    }

    private void drawSeries(Canvas canvas, List<RssiPoint> points, int color, long minTime, float timeRange,
                             float minRssi, float rssiRange, float chartWidth, float chartHeight) {
        if (points.isEmpty()) return;

        if (points.size() == 1) {
            RssiPoint point = points.get(0);
            dotPaint.setColor(color);
            canvas.drawCircle(
                    xFor(point.getTimestamp(), minTime, timeRange, chartWidth),
                    yFor(point.getRssi(), minRssi, rssiRange, chartHeight),
                    6f, dotPaint);
            return;
        }

        linePaint.setColor(color);
        Path path = new Path();
        for (int i = 0; i < points.size(); i++) {
            RssiPoint point = points.get(i);
            float x = xFor(point.getTimestamp(), minTime, timeRange, chartWidth);
            float y = yFor(point.getRssi(), minRssi, rssiRange, chartHeight);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, linePaint);
    }

    private float xFor(long timestamp, long minTime, float timeRange, float chartWidth) {
        return leftPadding + ((timestamp - minTime) / timeRange) * chartWidth;
    }

    private float yFor(int rssi, float minRssi, float rssiRange, float chartHeight) {
        return topPadding + chartHeight - ((rssi - minRssi) / rssiRange) * chartHeight;
    }

    private static class Series {
        final String label;
        final List<RssiPoint> points;
        final int color;

        Series(String label, List<RssiPoint> points, int color) {
            this.label = label;
            this.points = points;
            this.color = color;
        }
    }
}
