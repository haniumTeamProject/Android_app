package org.mcsmtp.blescanner.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.mcsmtp.blescanner.R;
import org.mcsmtp.blescanner.ble.BleScanner;
import org.mcsmtp.blescanner.data.BeaconDevice;
import org.mcsmtp.blescanner.data.FilterConfig;
import org.mcsmtp.blescanner.data.RssiPoint;
import org.mcsmtp.blescanner.filter.FilterRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 상세 화면 (그래프 + 구간 마킹 + 확대 보기). Compose DeviceDetailScreen 이식.
 */
public class DeviceDetailActivity extends AppCompatActivity implements BleScanner.Listener, FilterRepository.Listener {

    public static final String EXTRA_MAC_ADDRESS = "extra_mac_address";

    private BleScanner bleScanner;
    private String macAddress;

    private TextView textMacAddress;
    private CheckBox checkboxOriginal;
    private LinearLayout containerMainFilters;
    private RssiChartView chartMain;
    private Button buttonMarkStart;
    private Button buttonMarkEnd;
    private Button buttonMarkReset;
    private TextView textEmpty;
    private View contentContainer;
    private TextView textStats;
    private View layoutZoomSection;
    private CheckBox checkboxZoomOriginal;
    private LinearLayout containerZoomFilters;
    private TextView textZoomInsufficient;
    private RssiChartView chartZoom;

    private List<RssiPoint> history = Collections.emptyList();
    private List<FilterConfig> filters = Collections.emptyList();

    private boolean showOriginal = true;
    private final Map<String, Boolean> selectedFilterIds = new HashMap<>();
    private Long markStart;
    private Long markEnd;
    private List<RssiPoint> rangeSnapshot;

    private boolean zoomShowOriginal = true;
    private final Map<String, Boolean> zoomSelectedFilterIds = new HashMap<>();
    private Long tappedTimestamp;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_detail);

        macAddress = getIntent().getStringExtra(EXTRA_MAC_ADDRESS);
        if (macAddress == null) {
            finish();
            return;
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        setTitle(macAddress);

        textMacAddress = findViewById(R.id.textMacAddress);
        checkboxOriginal = findViewById(R.id.checkboxOriginal);
        containerMainFilters = findViewById(R.id.containerMainFilters);
        chartMain = findViewById(R.id.chartMain);
        buttonMarkStart = findViewById(R.id.buttonMarkStart);
        buttonMarkEnd = findViewById(R.id.buttonMarkEnd);
        buttonMarkReset = findViewById(R.id.buttonMarkReset);
        textEmpty = findViewById(R.id.textEmpty);
        contentContainer = findViewById(R.id.contentContainer);
        textStats = findViewById(R.id.textStats);
        layoutZoomSection = findViewById(R.id.layoutZoomSection);
        checkboxZoomOriginal = findViewById(R.id.checkboxZoomOriginal);
        containerZoomFilters = findViewById(R.id.containerZoomFilters);
        textZoomInsufficient = findViewById(R.id.textZoomInsufficient);
        chartZoom = findViewById(R.id.chartZoom);
        chartZoom.setRssiStepSize(10);
        chartZoom.setZoomable(true);
        textMacAddress.setText(macAddress);

        checkboxOriginal.setChecked(showOriginal);
        checkboxOriginal.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showOriginal = isChecked;
            refreshMainChart();
        });

        checkboxZoomOriginal.setChecked(zoomShowOriginal);
        checkboxZoomOriginal.setOnCheckedChangeListener((buttonView, isChecked) -> {
            zoomShowOriginal = isChecked;
            refreshZoomChart();
        });

        buttonMarkStart.setOnClickListener(v -> {
            if (history.isEmpty()) return;
            markStart = history.get(history.size() - 1).getTimestamp();
            refreshMainChart();
        });

        buttonMarkEnd.setOnClickListener(v -> {
            if (markStart == null || history.isEmpty()) return;
            long end = history.get(history.size() - 1).getTimestamp();
            markEnd = end;
            long lo = Math.min(markStart, end);
            long hi = Math.max(markStart, end);

            List<RssiPoint> snapshot = new ArrayList<>();
            for (RssiPoint p : history) {
                if (p.getTimestamp() >= lo && p.getTimestamp() <= hi) snapshot.add(p);
            }
            rangeSnapshot = snapshot;
            tappedTimestamp = null;

            layoutZoomSection.setVisibility(View.VISIBLE);
            updateZoomSection();

            refreshMainChart();
            refreshStats();
        });

        buttonMarkReset.setOnClickListener(v -> {
            markStart = null;
            markEnd = null;
            rangeSnapshot = null;
            tappedTimestamp = null;
            layoutZoomSection.setVisibility(View.GONE);
            textZoomInsufficient.setVisibility(View.GONE);
            chartZoom.setVisibility(View.VISIBLE);
            textStats.setVisibility(View.GONE);
            refreshMainChart();
        });

        chartZoom.setOnTapListener(timestamp -> {
            tappedTimestamp = timestamp;
            refreshZoomChart();
        });

        refreshMainChart();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bleScanner = BleScanner.getInstance(this);
        bleScanner.addListener(this);
        FilterRepository.getInstance().addListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        bleScanner.removeListener(this);
        FilterRepository.getInstance().removeListener(this);
    }

    @Override
    public void onScanUpdate(Map<String, BeaconDevice> devices, Map<String, List<RssiPoint>> historyMap) {
        List<RssiPoint> updated = historyMap.get(macAddress);
        if (updated == null) updated = Collections.emptyList();
        // BleScanner는 실제로 갱신된 주소의 리스트만 새 객체로 교체하므로,
        // 참조가 그대로면 이 화면과 무관한(다른 기기) 스캔 결과 - 다시 그릴 필요 없다.
        if (updated == history) return;
        history = updated;
        refreshMainChart();
    }

    @Override
    public void onFiltersChanged(List<FilterConfig> newFilters) {
        filters = newFilters;
        rebuildFilterCheckboxes(containerMainFilters, selectedFilterIds, this::refreshMainChart);
        rebuildFilterCheckboxes(containerZoomFilters, zoomSelectedFilterIds, this::refreshZoomChart);
        refreshMainChart();
        refreshZoomChart();
    }

    private void rebuildFilterCheckboxes(LinearLayout container, Map<String, Boolean> selectionMap, Runnable onChange) {
        container.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int swatchSize = (int) (14 * density);
        int swatchMargin = (int) (8 * density);
        int rowPadding = (int) (4 * density);

        for (FilterConfig filter : filters) {
            if (!selectionMap.containsKey(filter.getId())) {
                selectionMap.put(filter.getId(), true);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, rowPadding, 0, rowPadding);

            // 색 네모
            View colorSwatch = new View(this);
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(swatchSize, swatchSize);
            swatchParams.setMarginEnd(swatchMargin);
            colorSwatch.setLayoutParams(swatchParams);
            colorSwatch.setBackgroundColor(RssiChartView.getColorForFilterName(filter.getName()));
            row.addView(colorSwatch);

            // 체크박스
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(filter.getName());
            checkBox.setChecked(Boolean.TRUE.equals(selectionMap.get(filter.getId())));
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                selectionMap.put(filter.getId(), isChecked);
                onChange.run();
            });
            row.addView(checkBox);

            container.addView(row);
        }
    }

    private List<FilterConfig> activeFilters(Map<String, Boolean> selectionMap) {
        List<FilterConfig> active = new ArrayList<>();
        for (FilterConfig filter : filters) {
            if (Boolean.TRUE.equals(selectionMap.get(filter.getId()))) {
                active.add(filter);
            }
        }
        return active;
    }

    private void refreshMainChart() {
        boolean hasHistory = !history.isEmpty();
        textEmpty.setVisibility(hasHistory ? View.GONE : View.VISIBLE);
        contentContainer.setVisibility(hasHistory ? View.VISIBLE : View.GONE);
        if (!hasHistory) return;

        chartMain.setData(history, showOriginal, activeFilters(selectedFilterIds), markStart, markEnd);
        buttonMarkEnd.setEnabled(markStart != null);

        if (rangeSnapshot != null) refreshStats();
    }

    private void refreshStats() {
        if (rangeSnapshot == null || rangeSnapshot.isEmpty() || markStart == null || markEnd == null) {
            textStats.setVisibility(View.GONE);
            return;
        }

        long lo = Math.min(markStart, markEnd);
        long hi = Math.max(markStart, markEnd);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.getDefault(), "선택 구간 비교 (%d초)\n", (hi - lo) / 1000));

        if (showOriginal) {
            appendStatsLine(sb, "원본", rangeSnapshot);
        }
        for (FilterConfig filter : activeFilters(selectedFilterIds)) {
            List<RssiPoint> filtered = FilterRepository.applyFilter(rangeSnapshot, filter);
            appendStatsLine(sb, filter.getName(), filtered);
        }

        textStats.setText(sb.toString().trim());
        textStats.setVisibility(View.VISIBLE);
    }

    private void appendStatsLine(StringBuilder sb, String label, List<RssiPoint> points) {
        if (points.isEmpty()) return;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        for (RssiPoint p : points) {
            min = Math.min(min, p.getRssi());
            max = Math.max(max, p.getRssi());
            sum += p.getRssi();
        }
        double avg = sum / (double) points.size();
        sb.append(String.format(Locale.getDefault(), "%s: 최소 %d, 최대 %d, 평균 %.1f\n", label, min, max, avg));
    }

    /** 새 구간 지정 직후 호출: 데이터가 부족하면 안내 문구로 대체하고, 아니면 확대 그래프를 그린다. */
    private void updateZoomSection() {
        if (rangeSnapshot == null || rangeSnapshot.size() < 2) {
            chartZoom.setVisibility(View.GONE);
            textZoomInsufficient.setVisibility(View.VISIBLE);
            return;
        }
        chartZoom.setVisibility(View.VISIBLE);
        textZoomInsufficient.setVisibility(View.GONE);
        refreshZoomChart();
    }

    private void refreshZoomChart() {
        if (rangeSnapshot == null || rangeSnapshot.size() < 2) return;
        chartZoom.setData(rangeSnapshot, zoomShowOriginal, activeFilters(zoomSelectedFilterIds), null, null);
        chartZoom.setTappedTimestamp(tappedTimestamp);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
