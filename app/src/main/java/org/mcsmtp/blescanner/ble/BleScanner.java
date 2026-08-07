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

import org.json.JSONException;
import org.json.JSONObject;

import org.mcsmtp.blescanner.WebSocketManager;
import org.mcsmtp.blescanner.data.BeaconDevice;
import org.mcsmtp.blescanner.data.RssiPoint;
import org.mcsmtp.blescanner.speech.SpeechGuide;

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

    // 서버로 전송할 비콘 이름 접두사 (테스트용 — 이 접두사로 시작하는 이름만 웹소켓으로 보냄)
    private static final String SERVER_SEND_NAME_PREFIX = "ESP32";

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private final WebSocketManager webSocketManager;

    // 서버가 내려준 안내 문장을 읽어주는 TTS.
    // 판정은 서버가 하고 앱은 받은 문장을 읽기만 한다 — 문구를 바꿔도 앱을 다시 빌드할 필요가 없고,
    // /monitor를 안 열어놔도 동작한다.
    private final SpeechGuide speechGuide = new SpeechGuide();

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

        // 서버가 비콘 전환을 판단해서 내려주는 안내 메시지를 받아 음성으로 읽어준다
        speechGuide.init(appContext);
        webSocketManager.setMessageListener(this::onServerMessage);
    }

    // ---- 서버 안내(음성) ----

    public SpeechGuide getSpeechGuide() {
        return speechGuide;
    }

    private void onServerMessage(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            JSONObject msg = new JSONObject(text);
            if (!"guide".equals(msg.optString("type"))) return;   // RSSI 중계분 등은 무시

            String speech = msg.optString("speech", "");
            if (!speech.isEmpty()) speechGuide.speak(speech);
        } catch (JSONException e) {
            // RSSI 브로드캐스트 등 안내가 아닌 메시지도 같이 들어오므로 조용히 넘어간다
            Log.v(LOG_TAG, "안내 메시지 아님: " + text);
        }
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

    // ---- 측정 구간 제어 ----
    // 서버(/monitor)에서 버튼을 누르는 대신, 실제로 걸어다니는 폰에서 구간을 지정할 수 있게
    // "type":"measure" 형태의 제어 JSON을 웹소켓으로 보낸다. RSSI 전송과 같은 연결을 쓴다.
    private static final String MEASURE_TYPE = "measure";

    private String measureSessionId = null;
    private String measureLabel = null;
    private int markCount = 0;

    public boolean isMeasuring() {
        return measureSessionId != null;
    }

    public String getMeasureLabel() {
        return measureLabel;
    }

    /** 측정 시작을 서버에 알린다. 성공하면 세션 ID를 반환, 실패하면 null. */
    public String startMeasurement(String label) {
        if (!webSocketManager.isConnected()) return null;

        String sessionId = newSessionId();
        JSONObject payload = baseMeasurePayload("start", sessionId);
        if (payload == null) return null;
        try {
            payload.put("label", label == null ? "" : label);
        } catch (JSONException e) {
            Log.e(LOG_TAG, "측정 시작 JSON 생성 실패", e);
            return null;
        }

        if (!webSocketManager.sendControl(payload)) return null;

        measureSessionId = sessionId;
        measureLabel = label;
        markCount = 0;
        return sessionId;
    }

    /** 측정 중 특정 지점(비콘 통과 등)을 표시한다. 표시된 누적 개수를 반환. */
    public int markMeasurement(String label) {
        if (!isMeasuring()) return 0;

        JSONObject payload = baseMeasurePayload("mark", measureSessionId);
        if (payload == null) return markCount;
        try {
            payload.put("label", label == null ? "" : label);
        } catch (JSONException e) {
            Log.e(LOG_TAG, "지점 표시 JSON 생성 실패", e);
            return markCount;
        }

        if (webSocketManager.sendControl(payload)) markCount += 1;
        return markCount;
    }

    /** 측정 종료를 서버에 알린다. 종료된 측정 이름을 반환. */
    public String stopMeasurement() {
        if (!isMeasuring()) return null;

        JSONObject payload = baseMeasurePayload("end", measureSessionId);
        if (payload != null) {
            try {
                payload.put("label", measureLabel == null ? "" : measureLabel);
                payload.put("markCount", markCount);
            } catch (JSONException e) {
                Log.e(LOG_TAG, "측정 종료 JSON 생성 실패", e);
            }
            webSocketManager.sendControl(payload);
        }

        String finished = measureLabel;
        measureSessionId = null;
        measureLabel = null;
        markCount = 0;
        return finished;
    }

    private JSONObject baseMeasurePayload(String event, String sessionId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", MEASURE_TYPE);
            obj.put("event", event);
            obj.put("sessionId", sessionId);
            obj.put("timestamp", System.currentTimeMillis());
            obj.put("device", android.os.Build.MODEL);
            return obj;
        } catch (JSONException e) {
            Log.e(LOG_TAG, "측정 제어 JSON 생성 실패", e);
            return null;
        }
    }

    // 서버가 여러 폰의 측정을 구분할 수 있도록 시각 + 난수로 세션 ID를 만든다
    private String newSessionId() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date())
                + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
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

            // 서버 실시간 전송 — 테스트용으로 이름이 "ESP32"로 시작하는 비콘만 보냄.
            // (화면 목록/이력에는 위에서 이미 전체 기기를 다 넣었으므로 앱에서는 전부 보이고, 웹소켓 전송만 걸러짐)
            if (name.startsWith(SERVER_SEND_NAME_PREFIX)) {
                String key = address + "|" + name;
                bleRssiMap.put(key, rssi);   // 누적 맵은 Survey 기능 호환을 위해 그대로 유지

                String log = name + ", " + address + ", " + rssi;
                Log.d(LOG_TAG, log);

                // 이번에 실제로 스캔된 비콘 하나만 보낸다.
                // 예전에는 누적 맵(bleRssiMap) 전체를 매번 보냈는데, 그러면 아직 다시 스캔되지
                // 않은 비콘의 옛 값이 계속 반복 전송된다. 서버의 칼만 필터는 그 반복값을 매번
                // 새 측정으로 받아들여 같은 값으로 계속 수렴하고, 그러다 실제 새 값이 오면
                // 방향이 꺾이면서 톱니 모양 파형이 생긴다(폰을 가만히 둬도 나타남).
                // 추세 계산도 "움직임"이 아니라 "재스캔까지 걸린 시간"을 재게 되어 오판의 원인이 된다.
                HashMap<String, Object> single = new HashMap<>();
                single.put(key, rssi);
                webSocketManager.send(single);
            }

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
