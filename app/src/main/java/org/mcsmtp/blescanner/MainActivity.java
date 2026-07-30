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

    @Override
    protected void onStart() {
        super.onStart();
        bleScanner.addListener(this);
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
        List<BeaconDevice> sorted = new ArrayList<>(latestDevices.values());
        Collections.sort(sorted, (a, b) -> Integer.compare(b.getRssi(), a.getRssi()));
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
