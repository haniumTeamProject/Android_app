package org.mcsmtp.blescanner;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.mcsmtp.blescanner.ble.BleScanner;
import org.mcsmtp.blescanner.data.BeaconDevice;
import org.mcsmtp.blescanner.data.RssiPoint;
import org.mcsmtp.blescanner.ui.DeviceDetailActivity;
import org.mcsmtp.blescanner.ui.DeviceListAdapter;
import org.mcsmtp.blescanner.ui.FilterManageActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.mcsmtp.blescanner.ui.SurveyActivity;

public class MainActivity extends AppCompatActivity implements BleScanner.Listener {

    private static final int PERMISSION_REQUEST_CODE = 1000;
    private static final String PREFS_NAME = "ble_scanner_prefs";
    private static final String KEY_WS_URL = "ws_url";

    private BleScanner bleScanner;
    private DeviceListAdapter adapter;
    private EditText editServerUrl;
    private Button btnConnect;
    private TextView textConnectionStatus;

    private EditText editMeasureLabel;
    private Button btnMeasureStart;
    private Button btnMeasureMark;
    private Button btnMeasureEnd;
    private TextView textMeasureStatus;

    // BLE 스캔 결과는 초당 여러 번 올 수 있는데, 그때마다 목록 전체를 다시 그리면
    // 항목을 탭하는 도중에 뷰가 교체되면서 탭 제스처가 취소될 수 있다. 목록 갱신만 최소 간격을 둔다.
    private static final long LIST_REFRESH_THROTTLE_MS = 300;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastListRefreshTime = 0L;
    private boolean listRefreshScheduled = false;
    private Map<String, BeaconDevice> latestDevices = Collections.emptyMap();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RecyclerView recyclerView = findViewById(R.id.recyclerDevices);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceListAdapter(device -> {
            Intent intent = new Intent(this, DeviceDetailActivity.class);
            intent.putExtra(DeviceDetailActivity.EXTRA_MAC_ADDRESS, device.getMacAddress());
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);

        bleScanner = BleScanner.getInstance(this);

        editServerUrl = findViewById(R.id.editServerUrl);
        btnConnect = findViewById(R.id.btnConnect);
        textConnectionStatus = findViewById(R.id.textConnectionStatus);

        // 마지막으로 쓴 주소가 있으면 그걸, 없으면 BleScanner 기본값을 입력창에 채워둠
        String savedUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_WS_URL, null);
        editServerUrl.setText(savedUrl != null ? savedUrl : bleScanner.getServerUrl());
        textConnectionStatus.setText(R.string.status_disconnected);

        btnConnect.setOnClickListener(v -> startConnection());

        editMeasureLabel = findViewById(R.id.editMeasureLabel);
        btnMeasureStart = findViewById(R.id.btnMeasureStart);
        btnMeasureMark = findViewById(R.id.btnMeasureMark);
        btnMeasureEnd = findViewById(R.id.btnMeasureEnd);
        textMeasureStatus = findViewById(R.id.textMeasureStatus);

        btnMeasureStart.setOnClickListener(v -> startMeasurement());
        btnMeasureMark.setOnClickListener(v -> markMeasurement());
        btnMeasureEnd.setOnClickListener(v -> endMeasurement());
        updateMeasureUi();

        if (hasAllPermissions()) {
            startConnection();
        } else {
            requestMissingPermissions();
        }
    }

    // 입력창의 웹소켓 주소로 (재)연결. 이미 스캔 중이었으면 먼저 끊고 새 주소로 다시 시작한다.
    private void startConnection() {
        String url = editServerUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.server_url_hint, Toast.LENGTH_SHORT).show();
            return;
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_WS_URL, url).apply();

        if (bleScanner.isScanning()) {
            bleScanner.stopScan();
        }
        bleScanner.setServerUrl(url);

        if (hasAllPermissions()) {
            bleScanner.startScan();
            textConnectionStatus.setText(getString(R.string.status_connecting, url));
        } else {
            requestMissingPermissions();
        }
    }

    // ---- 측정 구간 제어 ----
    // 서버 화면에서 시작/종료를 누르는 대신, 실제로 걸어다니는 폰에서 구간을 지정한다.
    // 폰이 웹소켓으로 제어 메시지를 보내면 서버가 그대로 중계하고 /monitor가 받아서 처리한다.
    private void startMeasurement() {
        String label = editMeasureLabel.getText().toString().trim();
        String sessionId = bleScanner.startMeasurement(label);
        if (sessionId == null) {
            Toast.makeText(this, R.string.measure_need_connection, Toast.LENGTH_SHORT).show();
            return;
        }
        updateMeasureUi();
    }

    private void markMeasurement() {
        // 표시 이름을 따로 안 적었으면 몇 번째 지점인지만 남긴다
        String label = editMeasureLabel.getText().toString().trim();
        int count = bleScanner.markMeasurement(label.isEmpty() ? "지점" : label);
        if (count > 0) {
            Toast.makeText(this, getString(R.string.measure_marked, count), Toast.LENGTH_SHORT).show();
        }
    }

    private void endMeasurement() {
        String finished = bleScanner.stopMeasurement();
        updateMeasureUi();
        if (finished != null && !finished.isEmpty()) {
            Toast.makeText(this, getString(R.string.measure_finished, finished), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateMeasureUi() {
        boolean measuring = bleScanner.isMeasuring();
        btnMeasureStart.setEnabled(!measuring);
        btnMeasureMark.setEnabled(measuring);
        btnMeasureEnd.setEnabled(measuring);
        editMeasureLabel.setEnabled(!measuring);

        if (measuring) {
            String label = bleScanner.getMeasureLabel();
            textMeasureStatus.setText(getString(R.string.measure_running,
                    label == null || label.isEmpty() ? "(이름 없음)" : label));
        } else {
            textMeasureStatus.setText(R.string.measure_idle);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bleScanner.addListener(this);
        // BleScanner가 싱글턴이라 화면을 나갔다 와도 측정 상태가 유지됨 — 버튼 상태를 실제 상태에 맞춘다
        updateMeasureUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        bleScanner.removeListener(this);
        if (isFinishing()) {
            bleScanner.stopScan();
        }
    }

    @Override
    public void onScanUpdate(Map<String, BeaconDevice> devices, Map<String, List<RssiPoint>> history) {
        latestDevices = devices;
        scheduleListRefresh();
    }

    private void scheduleListRefresh() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastListRefreshTime;
        if (elapsed >= LIST_REFRESH_THROTTLE_MS) {
            lastListRefreshTime = now;
            refreshDeviceList();
        } else if (!listRefreshScheduled) {
            listRefreshScheduled = true;
            mainHandler.postDelayed(() -> {
                listRefreshScheduled = false;
                lastListRefreshTime = System.currentTimeMillis();
                refreshDeviceList();
            }, LIST_REFRESH_THROTTLE_MS - elapsed);
        }
    }

    private void refreshDeviceList() {
        // 예전엔 RSSI 내림차순으로 정렬해서, 신호 세기가 바뀔 때마다 항목 순서가 계속 뒤바뀌어
        // 원하는 기기를 누르려는 순간 다른 항목이 그 자리로 올라오는 문제가 있었다.
        // MAC 주소 기준 고정 정렬로 바꿔서 한 번 자리를 잡으면 위치가 변하지 않게 한다.
        List<BeaconDevice> sorted = new ArrayList<>(latestDevices.values());
        Collections.sort(sorted, (a, b) -> a.getMacAddress().compareTo(b.getMacAddress()));
        adapter.submitList(sorted);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_manage_filters) {
            startActivity(new Intent(this, FilterManageActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_survey) {          // ← 추가
            startActivity(new Intent(this, SurveyActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean hasAllPermissions() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return true;
            for (String permission : info.requestedPermissions) {
                if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private void requestMissingPermissions() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return;

            List<String> needed = new ArrayList<>();
            for (String permission : info.requestedPermissions) {
                if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(permission);   // 일단 모으기만
                }
            }

            if (!needed.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                        needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);   // 한 번에 요청
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.d("PermissionCheck", "패키지 정보 없음");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                            @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && hasAllPermissions()) {
            startConnection();
        }
    }
}
