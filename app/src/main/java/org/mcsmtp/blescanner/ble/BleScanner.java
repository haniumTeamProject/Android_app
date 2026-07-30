package org.mcsmtp.blescanner.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.util.Log;

import org.mcsmtp.blescanner.WebSocketManager;
import org.mcsmtp.blescanner.data.BeaconDevice;
import org.mcsmtp.blescanner.data.RssiPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BLE 스캔 + 화면 표시용 기기/이력 관리(참조 코틀린 버전에서 이식) +
 * 서버 실시간 전송(원본 MainActivity 로직 그대로 유지).
 */
public class BleScanner {

    public interface Listener {
        void onScanUpdate(Map<String, BeaconDevice> devices, Map<String, List<RssiPoint>> history);
    }

    private static final String LOG_TAG = "BLETEST";

    private static volatile BleScanner instance;

    public static BleScanner getInstance(Context context) {
        if (instance == null) {
            synchronized (BleScanner.class) {
                if (instance == null) {
                    instance = new BleScanner(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // 웹소켓 주소를 따로 안 정해줬을 때 쓰는 기본값 (MainActivity에서 입력 후 setServerUrl로 바꿀 수 있음)
    private static final String DEFAULT_SERVER_URL = "wss://hanium.mcsmtp.org/ws";

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private final WebSocketManager webSocketManager;

    // 서버 전송용 원본 로직에서 쓰던 맵 (원본 그대로 유지)
    private final HashMap<String, Object> bleRssiMap = new HashMap<>();

    private final Map<String, BeaconDevice> scannedDevices = new LinkedHashMap<>();
    private final Map<String, List<RssiPoint>> rssiHistory = new LinkedHashMap<>();
    private static final int MAX_HISTORY_SIZE = 200;

    private boolean isScanning = false;

    private BleScanner(Context appContext) {
        bluetoothManager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
        webSocketManager = new WebSocketManager(DEFAULT_SERVER_URL);
    }

    // 앱 실행 후 사용자가 직접 입력한 웹소켓 주소로 바꿔 씀 (연결돼 있었다면 재연결 필요 — MainActivity에서 stopScan 후 startScan으로 처리)
    public void setServerUrl(String url) {
        webSocketManager.setServerUrl(url);
    }

    public String getServerUrl() {
        return webSocketManager.getServerUrl();
    }

    public boolean isScanning() {
        return isScanning;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onScanUpdate(getScannedDevices(), getRssiHistory());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized Map<String, BeaconDevice> getScannedDevices() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(scannedDevices));
    }

    public synchronized Map<String, List<RssiPoint>> getRssiHistory() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(rssiHistory));
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (isScanning) return;

        if (bluetoothLeScanner == null) {
            Log.d(LOG_TAG, "BluetoothLeScanner null");
            return;
        }

        webSocketManager.connect();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        bluetoothLeScanner.startScan(null, settings, leScanCallback);
        isScanning = true;
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!isScanning) return;
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(leScanCallback);
        }
        webSocketManager.disconnect();
        isScanning = false;
    }
    // ↓ 추가
    private void clearRssiMap() {
        bleRssiMap.clear();
    }
    public void startSurvey(org.json.JSONObject meta) {
        clearRssiMap();
        webSocketManager.setSurveyMeta(meta);
    }

    public void markSurveyEvent(String event) {
        // 측정 화면에서 [Waypoint 통과]/[정지시작]/[정지끝] 버튼 누를 때 호출
        webSocketManager.markEvent(event); // WebSocketManager에 이 메서드도 추가 필요 (아래 참고)
    }

    public void stopSurvey() {
        webSocketManager.clearSurveyMeta();
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            if (result == null || result.getDevice() == null) return;

            String address = result.getDevice().getAddress();
            int rssi = result.getRssi();

            if (rssi == 127) return;

            String name = null;
            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null) name = result.getDevice().getName();
            if (name == null) name = "unknown";

            long now = System.currentTimeMillis();

            // 화면 표시용 기기 목록/이력 갱신
            BeaconDevice beacon = new BeaconDevice(address, name, rssi, now);
            synchronized (BleScanner.this) {
                scannedDevices.put(address, beacon);

                List<RssiPoint> existing = rssiHistory.get(address);
                List<RssiPoint> points = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
                points.add(new RssiPoint(now, rssi));
                while (points.size() > MAX_HISTORY_SIZE) {
                    points.remove(0);
                }
                rssiHistory.put(address, points);
            }

            // 서버 실시간 전송 (특정 MAC만 걸러내던 필터 제거, 스캔된 전체 기기 전송)
            bleRssiMap.put(address + "|" + name, rssi);

            String log = name + ", " + address + ", " + rssi;
            Log.d(LOG_TAG, log);

            webSocketManager.send(bleRssiMap);

            notifyListeners();
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            isScanning = false;
            Log.e(LOG_TAG, "스캔 실패: errorCode=" + errorCode);
        }
    };

    // 스캔 결과가 올 때마다 바로 알린다 (그래프가 부드럽게 갱신되도록).
    // 목록 화면처럼 잦은 갱신이 문제가 되는 화면은 각자 필요하면 자체적으로 갱신 빈도를 조절한다.
    private void notifyListeners() {
        Map<String, BeaconDevice> devices = getScannedDevices();
        Map<String, List<RssiPoint>> history = getRssiHistory();
        for (Listener listener : listeners) {
            listener.onScanUpdate(devices, history);
        }
    }
}
