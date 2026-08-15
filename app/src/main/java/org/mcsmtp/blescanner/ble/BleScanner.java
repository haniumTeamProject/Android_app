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

    // iBeacon 광고에서 major/minor 를 뽑기 위한 값.
    //
    // 제조사 데이터의 회사 ID 0x004C(Apple) 아래에 iBeacon 규격이 실린다.
    //
    //   [0]=0x02 [1]=0x15  [2..17]=UUID(16)  [18..19]=major  [20..21]=minor  [22]=txPower
    //
    // 서버는 **major/minor 로 비콘을 가린다.** major = 100 + 층번호 라서 층까지 한 번에
    // 나오고, minor 는 펌웨어에 새겨 넣는 논리 번호라 기기를 교체해도 그대로다.
    // (MAC 은 기기를 바꾸면 달라져서 그때마다 DB 를 다시 입력해야 한다)
    private static final int APPLE_COMPANY_ID = 0x004C;
    private static final byte IBEACON_TYPE = 0x02;
    private static final byte IBEACON_LENGTH = 0x15;

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

    // ---- 스캔 워치독 ----
    // 안드로이드는 오래 연속 스캔하면 오류(onScanFailed) 없이 조용히 결과 전달을 멈추는 경우가 있다.
    // 그러면 앱은 스캔 중이라고 믿고 있는데 실제로는 아무것도 안 들어와서, 사람이 "연결"을 다시
    // 누를 때까지 데이터가 끊긴다. 그래서 일정 시간 결과가 없으면 스캔을 자동으로 다시 시작한다.
    // (실사용 BLE 라이브러리들도 같은 이유로 주기적으로 스캔을 재시작한다)
    private static final long SCAN_STALL_MS = 5000;      // 이 시간 동안 결과가 없으면 멈춘 것으로 본다
    private static final long SCAN_RESTART_MIN_MS = 10000; // 재시작 최소 간격 (아래 주석 참고)
    private static final long WATCHDOG_PERIOD_MS = 2000;

    // 재시작해도 안 살아나면 간격을 늘린다. 안드로이드는 30초에 startScan 5회를 넘기면 앱의
    // 스캔을 차단하는데, 계속 두드리면 차단 창이 갱신되어 오히려 회복을 막는다.
    private static final long SCAN_RESTART_BACKOFF_1 = 30000;
    private static final long SCAN_RESTART_BACKOFF_2 = 60000;

    // 멈춘 뒤에 되살리는 것보다, 애초에 억제가 쌓이지 않게 주기적으로 스캔을 새로 시작한다.
    // 안드로이드 제한(30초에 5회)을 고려해 25초 간격 = 30초당 1.2회로 잡았다.
    private static final long SCAN_REFRESH_PERIOD_MS = 25000;

    private volatile long lastScanResultAt = 0;
    private volatile long lastScanRestartAt = 0;
    private int scanRestartCount = 0;
    private int failedRestarts = 0;                       // 재시작했는데도 결과가 안 온 횟수
    private long restartIntervalMs = SCAN_RESTART_MIN_MS;
    private volatile String lastScanIssue = "";           // 화면에 이유를 보여주기 위함

    public String getLastScanIssue() {
        return lastScanIssue;
    }

    private final android.os.Handler watchdogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable watchdogTask = new Runnable() {
        @Override
        public void run() {
            checkScanAlive();
            watchdogHandler.postDelayed(this, WATCHDOG_PERIOD_MS);
        }
    };

    /**
     * BluetoothLeScanner를 다시 가져온다.
     *
     * 생성자에서 한 번 받아둔 객체를 계속 쓰면, 블루투스를 껐다 켰을 때 그 객체가 무효가 된다.
     * null이 아니라서 startScan()이 예외도 없이 조용히 아무 일도 안 하게 되고, 워치독이
     * 아무리 재시작해도 죽은 스캐너에 대고 재시작하는 셈이라 영원히 안 살아난다.
     */
    /**
     * 스캔 설정. 기본값으로 두면 안드로이드가 "같은 내용의 광고"를 억제해서, 이름·데이터가
     * 항상 똑같은 정적 비콘(ESP32)은 한동안 보고되다가 조용히 끊긴다. 반면 데이터가 계속
     * 바뀌는 기기(로봇청소기 등)는 매번 새 광고로 인식돼 계속 들어온다.
     * 실제로 "위치는 그대로인데 ESP32만 안 잡히는" 현상이 이것 때문이었다.
     */
    private ScanSettings buildScanSettings() {
        ScanSettings.Builder b = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0);   // 배치 모드는 중복을 합쳐버리므로 즉시 보고

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // 기기당 보고할 광고 수를 최대로. 기본값이 억제의 주된 원인이다.
            b.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
             .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE);
        }
        return b.build();
    }

    private boolean refreshScanner() {
        if (bluetoothManager != null) bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            lastScanIssue = "블루투스 어댑터 없음";
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            lastScanIssue = "블루투스 꺼짐";
            return false;
        }
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            lastScanIssue = "스캐너를 가져올 수 없음";
            return false;
        }
        return true;
    }

    /** 스캔을 중지했다 다시 시작한다. 중복 억제 캐시를 비우는 효과가 있다. */
    @SuppressLint("MissingPermission")
    private void restartScanNow(String reason) {
        // 어떤 경로로 끝나든 다음 시도까지 간격을 둔다.
        // (실패해서 일찍 빠져나갈 때 이걸 안 찍으면 워치독 주기마다 계속 재시도하게 됨)
        lastScanRestartAt = System.currentTimeMillis();

        if (!refreshScanner()) {
            Log.w(LOG_TAG, "스캔 재시작 불가(" + reason + "): " + lastScanIssue);
            notifyScanStatus();
            return;
        }

        scanRestartCount++;
        Log.d(LOG_TAG, "스캔 재시작 [" + reason + "] " + scanRestartCount + "번째");

        try {
            bluetoothLeScanner.stopScan(leScanCallback);
        } catch (Exception e) {
            Log.e(LOG_TAG, "스캔 중지 실패", e);
        }
        try {
            bluetoothLeScanner.startScan(null, buildScanSettings(), leScanCallback);
        } catch (Exception e) {
            Log.e(LOG_TAG, "스캔 재시작 실패", e);
            lastScanIssue = "재시작 실패: " + e.getClass().getSimpleName();
        }
        notifyScanStatus();
    }

    @SuppressLint("MissingPermission")
    private void checkScanAlive() {
        if (!isScanning) return;

        long now = System.currentTimeMillis();
        if (lastScanResultAt == 0) lastScanResultAt = now;   // 시작 직후 유예

        if (now - lastScanResultAt < SCAN_STALL_MS) {
            // 결과가 잘 들어오고 있으면 재시도 상태를 원상복구
            if (failedRestarts != 0 || restartIntervalMs != SCAN_RESTART_MIN_MS) {
                failedRestarts = 0;
                restartIntervalMs = SCAN_RESTART_MIN_MS;
                lastScanIssue = "";
            }
            // 잘 돌고 있어도 주기적으로 한 번씩 새로 시작해서 중복 억제 캐시를 비운다.
            // (정적 광고를 쏘는 비콘이 조용히 보고에서 빠지는 것을 예방)
            if (now - lastScanRestartAt >= SCAN_REFRESH_PERIOD_MS) {
                restartScanNow("주기 갱신");
            }
            return;
        }
        if (now - lastScanRestartAt < restartIntervalMs) return;

        failedRestarts++;
        Log.w(LOG_TAG, "스캔이 " + (now - lastScanResultAt) + "ms 동안 멈춤 (연속 실패 "
                + failedRestarts + ")");
        restartScanNow("멈춤 감지");   // 블루투스 상태 확인·간격 갱신은 여기서 함께 처리

        // 재시작해도 계속 안 살아나면 간격을 늘려서 안드로이드 차단을 피한다
        if (failedRestarts >= 6) {
            restartIntervalMs = SCAN_RESTART_BACKOFF_2;
            lastScanIssue = "재시작 " + failedRestarts + "회 실패 — 60초 간격으로 대기";
        } else if (failedRestarts >= 3) {
            restartIntervalMs = SCAN_RESTART_BACKOFF_1;
            lastScanIssue = "재시작 " + failedRestarts + "회 실패 — 30초 간격으로 대기";
        }

        // lastScanResultAt은 여기서 건드리지 않는다.
        // 예전에는 재시작 직후 now로 갱신했는데, 그러면 실제 결과가 하나도 안 왔는데도
        // 잠시 "정상"으로 판정되어 실패 카운터(failedRestarts)가 초기화됐다.
        // 그 탓에 백오프가 영영 발동하지 않고 10초마다 계속 두드리게 된다.
        // 다음 재시작을 막는 건 lastScanRestartAt + restartIntervalMs 검사가 이미 하고 있다.
        notifyScanStatus();
        // 웹소켓 쪽은 여기서 건드리지 않는다. 끊김 처리는 WebSocketManager가 자체 재연결로
        // 이미 담당하고 있어서, 여기서 connect()를 또 부르면 소켓이 두 번 열릴 수 있다.
    }

    /** 마지막 수신 시각·재시작 횟수를 화면에 알리기 위한 콜백 */
    public interface ScanStatusListener {
        void onScanStatus(long msSinceLastResult, int restartCount);
    }

    private ScanStatusListener scanStatusListener;

    public void setScanStatusListener(ScanStatusListener l) {
        this.scanStatusListener = l;
    }

    /** 웹소켓 연결 상태를 화면에 표시하기 위한 통로 */
    public void setConnectionStatusListener(WebSocketManager.StatusListener l) {
        webSocketManager.setStatusListener(l);
    }

    private void notifyScanStatus() {
        ScanStatusListener l = scanStatusListener;
        if (l == null) return;
        long since = lastScanResultAt == 0 ? 0 : System.currentTimeMillis() - lastScanResultAt;
        l.onScanStatus(since, scanRestartCount);
    }

    public int getScanRestartCount() {
        return scanRestartCount;
    }

    public long getMsSinceLastScanResult() {
        return lastScanResultAt == 0 ? -1 : System.currentTimeMillis() - lastScanResultAt;
    }

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
            String type = msg.optString("type");

            if (DESTINATION_TYPE.equals(type)) {
                // 목적지 응답은 화면 쪽에서 처리한다. 되묻기면 안내를 읽은 뒤
                // 마이크를 다시 켜야 하는데, 그 흐름은 여기(백그라운드)가 아니라
                // 화면이 들고 있는 편이 자연스럽다.
                notifyDestination(msg);
                return;
            }

            if (!"guide".equals(type)) return;   // RSSI 중계분 등은 무시

            String speech = msg.optString("speech", "");
            if (!speech.isEmpty()) speechGuide.speak(speech);
        } catch (JSONException e) {
            // RSSI 브로드캐스트 등 안내가 아닌 메시지도 같이 들어오므로 조용히 넘어간다
            Log.v(LOG_TAG, "안내 메시지 아님: " + text);
        }
    }

    // ---- 음성 목적지 ----
    // 폰은 받아적은 문자열만 올려보내고, 어느 장소인지 판단하는 일은 서버가 한다.
    // 그래야 별칭이나 판정 기준을 고쳐도 앱을 다시 빌드하지 않아도 된다.
    private static final String DESTINATION_TYPE = "destination";

    /** 서버의 목적지 응답을 받는 콜백. 웹소켓 스레드에서 불린다. */
    public interface DestinationListener {
        void onDestinationMessage(JSONObject msg);
    }

    private volatile DestinationListener destinationListener;

    public void setDestinationListener(DestinationListener l) {
        this.destinationListener = l;
    }

    private void notifyDestination(JSONObject msg) {
        DestinationListener l = destinationListener;
        if (l != null) l.onDestinationMessage(msg);
    }

    /** 받아적은 목적지를 서버에 보낸다. */
    public boolean requestDestination(String heard) {
        return sendDestination("resolve", heard);
    }

    /** 되물었을 때의 대답("두 번째")을 보낸다. */
    public boolean chooseDestination(String heard) {
        return sendDestination("choose", heard);
    }

    /** 되묻기를 그만둔다. */
    public boolean cancelDestination() {
        return sendDestination("cancel", "");
    }

    private boolean sendDestination(String event, String heard) {
        if (!webSocketManager.isConnected()) {
            Log.w(LOG_TAG, "목적지 요청 실패 (연결 없음): " + heard);
            return false;
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", DESTINATION_TYPE);
            obj.put("event", event);
            obj.put("text", heard == null ? "" : heard);
            obj.put("requestId", newSessionId());
            obj.put("timestamp", System.currentTimeMillis());
            obj.put("device", android.os.Build.MODEL);
            return webSocketManager.sendControl(obj);
        } catch (JSONException e) {
            Log.e(LOG_TAG, "목적지 JSON 생성 실패", e);
            return false;
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

    /** 서버 웹소켓이 붙어 있는지. 스캔 중인지와는 별개다. */
    public boolean isConnectedToServer() {
        return webSocketManager.isConnected();
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

        // 생성자 때 받아둔 스캐너는 블루투스를 껐다 켜면 무효가 되므로 여기서 다시 가져온다
        if (!refreshScanner()) {
            Log.d(LOG_TAG, "스캔 시작 불가: " + lastScanIssue);
            return;
        }

        webSocketManager.connect();

        bluetoothLeScanner.startScan(null, buildScanSettings(), leScanCallback);
        isScanning = true;

        // 스캔을 새로 시작하면 중복 통계·재시도 상태도 새로 센다
        resetPacketStats();
        lastPacketNanos.clear();
        failedRestarts = 0;
        restartIntervalMs = SCAN_RESTART_MIN_MS;
        lastScanIssue = "";

        // 스캔이 조용히 죽는지 감시 시작
        lastScanResultAt = System.currentTimeMillis();
        lastScanRestartAt = System.currentTimeMillis();
        watchdogHandler.removeCallbacks(watchdogTask);
        watchdogHandler.postDelayed(watchdogTask, WATCHDOG_PERIOD_MS);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!isScanning) return;
        watchdogHandler.removeCallbacks(watchdogTask);   // 의도적 중지면 워치독도 끈다
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

    // ---- 중복 패킷 진단 ----
    // 같은 RSSI 값이 25개 연속으로 똑같이 나오는 구간이 관찰됐다. 실제 전파라면 ±1~2dB는
    // 흔들리므로, 안드로이드가 같은 패킷을 여러 번 전달하는지 확인이 필요하다.
    // ScanResult.getTimestampNanos()는 그 패킷이 실제로 관측된 시각이라, 값이 같으면
    // "새 패킷이 아니라 같은 패킷의 재전달"이라는 뜻이다.
    private final Map<String, Long> lastPacketNanos = new HashMap<>();

    // 로그를 뒤지지 않고 화면에서 바로 볼 수 있도록 중복 비율을 세어둔다
    private volatile int packetCount = 0;
    private volatile int duplicateCount = 0;

    /** 지금까지 받은 패킷 중 "같은 패킷 재전달"이었던 비율(%). 패킷이 없으면 -1 */
    public int getDuplicatePercent() {
        int n = packetCount;
        return n == 0 ? -1 : (duplicateCount * 100) / n;   // 0%와 "데이터 없음"을 구분
    }

    public int getPacketCount() {
        return packetCount;
    }

    public void resetPacketStats() {
        packetCount = 0;
        duplicateCount = 0;
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            if (result == null || result.getDevice() == null) return;

            // 워치독 기준점 — 결과가 들어오고 있다는 증거
            lastScanResultAt = System.currentTimeMillis();

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
            // 로그는 필터 바깥에 둔다. 안쪽에만 있으면 "스캔이 멈춘 것"과
            // "이름이 안 잡혀 전송에서 걸러진 것"을 로그로 구분할 수 없다.
            //
            // dt = 같은 기기의 직전 패킷과의 관측 시각 차이(ms).
            //   dt=0    -> 안드로이드가 같은 패킷을 다시 전달한 것 (중복)
            //   dt=60내외 -> 광고 주기(64ms)대로 들어온 새 패킷
            long packetNanos = result.getTimestampNanos();
            Long prevNanos = lastPacketNanos.put(address, packetNanos);
            long dtMs = (prevNanos == null) ? -1 : (packetNanos - prevNanos) / 1_000_000L;

            packetCount++;
            if (dtMs == 0) duplicateCount++;

            Log.d(LOG_TAG, name + ", " + address + ", " + rssi
                    + ", dt=" + (dtMs < 0 ? "첫패킷" : dtMs + "ms")
                    + (dtMs == 0 ? " [중복]" : ""));

            if (name.startsWith(SERVER_SEND_NAME_PREFIX)) {
                String key = address + "|" + name;
                bleRssiMap.put(key, rssi);   // 누적 맵은 Survey 기능 호환을 위해 그대로 유지

                // major/minor 를 같이 올린다.
                //
                // **키 형식(MAC|이름)은 그대로 둔다.** 서버와 /monitor 가 그 키로
                // 필터 상태·그래프 계열을 구분하고 있어서, 여기서 바꾸면 전부 어긋난다.
                // 그래서 rssi 는 지금처럼 보내고 식별자만 따로 얹는다.
                //
                // 키 이름을 "_ids" 로 한 이유: 서버는 payload 의 숫자 값을 전부 RSSI 로
                // 훑는데(handler._process_message), 이건 dict 라 그 루프가 건너뛴다.
                // 즉 **지금 서버를 안 고쳐도 깨지지 않는다.**
                int[] ids = parseIBeacon(result);
                // 로그를 남긴다. 안 남기면 "안 왔다"와 "못 읽었다"를 구분할 수 없어
                // 앱을 다시 깔았는지부터 의심하게 된다.
                Log.d(LOG_TAG, "iBeacon " + name + " → "
                        + (ids == null ? "major/minor 못 읽음 (iBeacon 광고가 아님)"
                                       : "major=" + ids[0] + " minor=" + ids[1]));
                HashMap<String, Object> single = new HashMap<>();
                single.put(key, rssi);
                if (ids != null) {
                    try {
                        JSONObject one = new JSONObject();
                        one.put("major", ids[0]);
                        one.put("minor", ids[1]);
                        JSONObject idMap = new JSONObject();
                        idMap.put(key, one);
                        single.put("_ids", idMap);
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "식별자 JSON 생성 실패", e);
                    }
                }

                // 이번에 실제로 스캔된 비콘 하나만 보낸다.
                // 예전에는 누적 맵(bleRssiMap) 전체를 매번 보냈는데, 그러면 아직 다시 스캔되지
                // 않은 비콘의 옛 값이 계속 반복 전송된다. 서버의 칼만 필터는 그 반복값을 매번
                // 새 측정으로 받아들여 같은 값으로 계속 수렴하고, 그러다 실제 새 값이 오면
                // 방향이 꺾이면서 톱니 모양 파형이 생긴다(폰을 가만히 둬도 나타남).
                // 추세 계산도 "움직임"이 아니라 "재스캔까지 걸린 시간"을 재게 되어 오판의 원인이 된다.
                webSocketManager.send(single);
            }

            notifyListeners();
        }

        /**
         * iBeacon 광고에서 major/minor 를 뽑는다. iBeacon 이 아니면 null.
         *
         * 길이와 머리 두 바이트(0x02 0x15)를 반드시 확인한다. 0x004C 는 애플이 쓰는
         * 회사 ID 라서 iBeacon 이 아닌 애플 기기(에어팟·핸드오프 등)도 같은 자리에
         * 자기 데이터를 싣는다. 확인 없이 읽으면 엉뚱한 바이트를 major 로 쓴다.
         */
        private int[] parseIBeacon(ScanResult result) {
            if (result.getScanRecord() == null) return null;
            android.util.SparseArray<byte[]> all =
                    result.getScanRecord().getManufacturerSpecificData();
            if (all == null) return null;

            // **회사 ID 를 정해놓고 찾지 않는다.**
            //
            // 규격대로면 애플의 0x004C 지만, 펌웨어가 그 값을 바이트 순서를 뒤집어
            // 넣으면 0x4C00 으로 잡힌다(우리 beacon.ino 가 setManufacturerId(0x4C00)).
            // 어느 쪽인지 확인하려고 기기를 다시 굽는 것보다, 들어온 것 중에서
            // iBeacon 모양인 것을 찾는 편이 확실하고 펌웨어가 바뀌어도 안 깨진다.
            for (int i = 0; i < all.size(); i++) {
                byte[] md = all.valueAt(i);
                if (md == null || md.length < 23) continue;
                if (md[0] != IBEACON_TYPE || md[1] != IBEACON_LENGTH) continue;

                int major = ((md[18] & 0xFF) << 8) | (md[19] & 0xFF);
                int minor = ((md[20] & 0xFF) << 8) | (md[21] & 0xFF);
                return new int[]{major, minor};
            }
            return null;
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
