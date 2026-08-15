package org.mcsmtp.blescanner;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.mcsmtp.blescanner.ble.BleScanner;
import org.mcsmtp.blescanner.data.BeaconDevice;
import org.mcsmtp.blescanner.data.RssiPoint;
import org.mcsmtp.blescanner.speech.VoiceInput;
import org.mcsmtp.blescanner.ui.DeviceDetailActivity;
import org.mcsmtp.blescanner.ui.DeviceListAdapter;
import org.mcsmtp.blescanner.ui.FilterManageActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    // 화면 상단 상태줄에 표시할 값들
    private String wsStatusText = "연결 안 됨";
    private int scanRestartCount = 0;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());

    private EditText editDeviceFilter;
    // 목록 표시용 검색어 (쉼표로 여러 개 = OR). 서버 전송 대상과는 무관하게 화면에만 적용된다.
    private List<String> deviceFilterTerms = Collections.emptyList();

    private EditText editMeasureLabel;
    private Button btnMeasureStart;
    private Button btnMeasureMark;
    private Button btnMeasureEnd;
    private TextView textMeasureStatus;

    // ---- 음성 목적지 ----
    private final VoiceInput voiceInput = new VoiceInput();
    private Button btnVoiceDestination;
    private TextView textDestinationStatus;

    // 서버가 되물은 상태인가. true면 다음 발화는 새 목적지가 아니라 "두 번째" 같은 선택이다.
    private boolean awaitingChoice = false;

    // 못 알아들었을 때 자동으로 다시 듣는 횟수. 무한 반복을 막는다 —
    // 주변이 시끄러우면 계속 실패하는데, 그때 마이크가 영원히 켜져 있으면 안 된다.
    private static final int MAX_AUTO_RETRY = 1;
    private int autoRetryCount = 0;

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

        // 실측 도구라 화면이 꺼지면 안 된다. 화면이 꺼지면 안드로이드가 BLE 스캔을
        // 제한하거나 Doze로 네트워크를 끊어서 데이터가 조용히 멈춘다.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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

        // 연결 상태와 스캔 상태를 실제로 화면에 반영한다.
        // 예전에는 "연결 시도 중"에서 영영 안 바뀌어서, 끊겨도 알 방법이 없었다.
        bleScanner.setConnectionStatusListener((text, connected) ->
                runOnUiThread(() -> {
                    wsStatusText = text;
                    updateStatusLine();
                }));
        bleScanner.setScanStatusListener((msSinceLast, restartCount) ->
                runOnUiThread(() -> {
                    scanRestartCount = restartCount;
                    updateStatusLine();
                }));

        editDeviceFilter = findViewById(R.id.editDeviceFilter);
        editDeviceFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                deviceFilterTerms = parseFilterTerms(s.toString());
                refreshDeviceList();   // 검색어는 즉시 반영 (스캔 결과를 기다리지 않도록)
            }
        });

        editMeasureLabel = findViewById(R.id.editMeasureLabel);
        btnMeasureStart = findViewById(R.id.btnMeasureStart);
        btnMeasureMark = findViewById(R.id.btnMeasureMark);
        btnMeasureEnd = findViewById(R.id.btnMeasureEnd);
        textMeasureStatus = findViewById(R.id.textMeasureStatus);

        CheckBox checkSpeech = findViewById(R.id.checkSpeech);
        checkSpeech.setChecked(bleScanner.getSpeechGuide().isEnabled());
        checkSpeech.setOnCheckedChangeListener(
                (buttonView, isChecked) -> bleScanner.getSpeechGuide().setEnabled(isChecked));

        btnMeasureStart.setOnClickListener(v -> startMeasurement());
        btnMeasureMark.setOnClickListener(v -> markMeasurement());
        btnMeasureEnd.setOnClickListener(v -> endMeasurement());
        updateMeasureUi();

        setupVoiceDestination();

        if (hasAllPermissions()) {
            startConnection();
        } else {
            requestMissingPermissions();
        }
    }

    // 연결 상태 + 마지막 수신 경과 + 스캔 재시작 횟수를 한 줄로 보여준다.
    // 현장에서 "지금 데이터가 오고 있는지"를 폰만 보고 알 수 있어야 하므로 1초마다 갱신한다.
    private void updateStatusLine() {
        if (textConnectionStatus == null) return;

        StringBuilder sb = new StringBuilder(wsStatusText);
        long since = bleScanner.getMsSinceLastScanResult();
        if (bleScanner.isScanning()) {
            if (since < 0) sb.append(" · 스캔 대기");
            else if (since < 2000) sb.append(" · 수신 중");
            else sb.append(String.format(Locale.US, " · %.0f초째 수신 없음", since / 1000.0));
        }
        if (scanRestartCount > 0) sb.append(" · 재시작 ").append(scanRestartCount).append("회");

        // 패킷 수를 같이 보여준다. 개수가 0이면 "중복 0%"가 아니라 "받은 게 없음"이다.
        if (bleScanner.isScanning()) {
            int dup = bleScanner.getDuplicatePercent();
            int cnt = bleScanner.getPacketCount();
            if (dup < 0) sb.append(" · 패킷 0개");
            else sb.append(" · 중복 ").append(dup).append("% (").append(cnt).append("개)");
        }

        String issue = bleScanner.getLastScanIssue();
        if (issue != null && !issue.isEmpty()) sb.append("\n⚠ ").append(issue);

        textConnectionStatus.setText(sb.toString());
    }

    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            updateStatusLine();
            statusHandler.postDelayed(this, 1000);
        }
    };

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

    // ---- 음성 목적지 ----
    //
    // 전체 흐름:
    //   [마이크 버튼] → 받아적기 → 서버(destination/resolve) → 응답
    //        └ resolved  : 안내를 읽고 끝
    //        └ ambiguous : 안내를 다 읽은 뒤 자동으로 다시 듣기 → destination/choose
    //        └ notFound  : 안내를 다 읽은 뒤 한 번만 다시 듣기
    //
    // 어느 장소인지 판단하는 일은 전부 서버가 한다. 폰은 받아적은 문자열을 올리고
    // 돌아온 speech를 읽어줄 뿐이다. 그래서 판정 기준을 고쳐도 앱을 다시 안 빌드해도 된다.
    private void setupVoiceDestination() {
        btnVoiceDestination = findViewById(R.id.btnVoiceDestination);
        textDestinationStatus = findViewById(R.id.textDestinationStatus);

        voiceInput.init(this);
        voiceInput.setListener(new VoiceInput.Listener() {
            @Override public void onHeard(String text) {
                textDestinationStatus.setText(getString(R.string.destination_thinking, text));
                boolean sent = awaitingChoice
                        ? bleScanner.chooseDestination(text)
                        : bleScanner.requestDestination(text);
                if (!sent) {
                    awaitingChoice = false;
                    speakAndShow(getString(R.string.destination_need_connection));
                }
            }

            @Override public void onFailed(String message) {
                // 못 알아들은 경우에만 한 번 더 듣는다. 권한/기기 문제는 다시 해도 똑같다.
                boolean retry = VoiceInput.isRetryable(message) && autoRetryCount < MAX_AUTO_RETRY;
                if (retry) autoRetryCount++;
                else resetDestinationFlow();

                textDestinationStatus.setText(message);
                bleScanner.getSpeechGuide().speak(message, retry ? voiceInput::listen : null);
            }

            @Override public void onListeningChanged(boolean listening) {
                btnVoiceDestination.setText(listening
                        ? R.string.action_voice_listening : R.string.action_voice_destination);
                if (listening) textDestinationStatus.setText(R.string.destination_listening);
            }
        });

        bleScanner.setDestinationListener(msg -> runOnUiThread(() -> onDestinationMessage(msg)));

        btnVoiceDestination.setOnClickListener(v -> {
            if (voiceInput.isListening()) {   // 듣는 중에 다시 누르면 취소
                voiceInput.cancel();
                resetDestinationFlow();
                textDestinationStatus.setText(R.string.destination_idle);
                return;
            }
            startVoiceDestination();
        });

        if (!voiceInput.isAvailable()) {
            btnVoiceDestination.setEnabled(false);
            textDestinationStatus.setText(R.string.destination_no_recognizer);
        } else {
            textDestinationStatus.setText(R.string.destination_idle);
        }
    }

    private void startVoiceDestination() {
        if (!bleScanner.isConnectedToServer()) {
            speakAndShow(getString(R.string.destination_need_connection));
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            textDestinationStatus.setText(R.string.destination_need_mic);
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
            return;
        }

        resetDestinationFlow();
        // 안내를 읽는 도중에 마이크를 켜면 그 소리를 그대로 받아적으므로 먼저 끊는다
        bleScanner.getSpeechGuide().stop();
        voiceInput.listen();
    }

    private void resetDestinationFlow() {
        awaitingChoice = false;
        autoRetryCount = 0;
    }

    /** 서버의 목적지 응답 처리. 메인 스레드에서 불린다. */
    private void onDestinationMessage(JSONObject msg) {
        String event = msg.optString("event");
        String speech = msg.optString("speech", "");

        if ("ambiguous".equals(event)) {
            // 되묻기 — 안내를 "다 읽은 뒤에" 마이크를 켜야 한다.
            // 읽는 중에 켜면 TTS 소리를 그대로 받아적는다.
            awaitingChoice = true;
            autoRetryCount = 0;
            textDestinationStatus.setText(getString(R.string.destination_asking,
                    describeCandidates(msg)));
            bleScanner.getSpeechGuide().speak(speech, voiceInput::listen);
            return;
        }

        if ("resolved".equals(event)) {
            resetDestinationFlow();
            JSONObject lm = msg.optJSONObject("landmark");
            String name = lm == null ? "" : lm.optString("name", "");
            textDestinationStatus.setText(getString(R.string.destination_resolved, name));
            bleScanner.getSpeechGuide().speak(speech);
            return;
        }

        if ("notFound".equals(event)) {
            boolean retry = autoRetryCount < MAX_AUTO_RETRY;
            if (retry) autoRetryCount++;
            else resetDestinationFlow();
            textDestinationStatus.setText(R.string.destination_not_found);
            bleScanner.getSpeechGuide().speak(speech, retry ? voiceInput::listen : null);
            return;
        }

        // cancelled, error 등 — 흐름만 되돌린다
        resetDestinationFlow();
        if (!speech.isEmpty()) bleScanner.getSpeechGuide().speak(speech);
    }

    private String describeCandidates(JSONObject msg) {
        org.json.JSONArray arr = msg.optJSONArray("candidates");
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.optString("name", ""));
        }
        return sb.toString();
    }

    private void speakAndShow(String message) {
        textDestinationStatus.setText(message);
        bleScanner.getSpeechGuide().speak(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bleScanner.addListener(this);
        // BleScanner가 싱글턴이라 화면을 나갔다 와도 측정 상태가 유지됨 — 버튼 상태를 실제 상태에 맞춘다
        updateMeasureUi();
        statusHandler.removeCallbacks(statusTicker);
        statusHandler.post(statusTicker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        bleScanner.removeListener(this);
        statusHandler.removeCallbacks(statusTicker);

        // 화면을 벗어나면 마이크는 반드시 끈다. 켜둔 채로 두면 배터리도 먹고,
        // 다른 화면에서 한 말이 목적지로 들어가버린다.
        voiceInput.cancel();
        resetDestinationFlow();

        if (isFinishing()) {
            bleScanner.stopScan();
            bleScanner.setDestinationListener(null);
            voiceInput.shutdown();
            // 앱을 끝내는 경우에만 TTS 자원을 반납한다 (화면 전환만으로 끊기면 안내가 끊기므로)
            bleScanner.getSpeechGuide().shutdown();
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
        List<BeaconDevice> shown = new ArrayList<>();
        for (BeaconDevice device : latestDevices.values()) {
            if (matchesDeviceFilter(device)) shown.add(device);
        }
        Collections.sort(shown, (a, b) -> a.getMacAddress().compareTo(b.getMacAddress()));
        adapter.submitList(shown);
    }

    // 쉼표로 구분된 검색어를 소문자로 정리 (빈 항목은 버림)
    private List<String> parseFilterTerms(String raw) {
        List<String> terms = new ArrayList<>();
        if (raw == null) return terms;
        for (String piece : raw.split(",")) {
            String term = piece.trim().toLowerCase(Locale.US);
            if (!term.isEmpty()) terms.add(term);
        }
        return terms;
    }

    // 검색어가 비어 있으면 전체 표시, 아니면 이름이나 MAC에 하나라도 걸리면 표시(OR)
    private boolean matchesDeviceFilter(BeaconDevice device) {
        if (deviceFilterTerms.isEmpty()) return true;

        String name = device.getName() == null ? "" : device.getName().toLowerCase(Locale.US);
        String mac = device.getMacAddress() == null ? "" : device.getMacAddress().toLowerCase(Locale.US);
        for (String term : deviceFilterTerms) {
            if (name.contains(term) || mac.contains(term)) return true;
        }
        return false;
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
        if (requestCode != PERMISSION_REQUEST_CODE) return;

        // 마이크 버튼을 눌러서 권한을 요청한 경우라면, 허용되는 즉시 듣기를 이어간다.
        // 사용자가 버튼을 한 번 더 눌러야 한다면 화면을 못 보는 사람에게는 불친절하다.
        for (int i = 0; i < permissions.length; i++) {
            if (!android.Manifest.permission.RECORD_AUDIO.equals(permissions[i])) continue;
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                startVoiceDestination();
            } else {
                speakAndShow(getString(R.string.destination_need_mic));
            }
            return;
        }

        if (hasAllPermissions()) {
            startConnection();
        }
    }
}
