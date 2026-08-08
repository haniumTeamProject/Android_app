package org.mcsmtp.blescanner;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;


public class WebSocketManager {

    /** 서버에서 내려온 메시지를 받아보는 콜백. OkHttp 백그라운드 스레드에서 호출된다. */
    public interface MessageListener {
        void onServerMessage(String text);
    }

    /** 연결 상태 변화 알림 (화면 표시용). 백그라운드 스레드에서 호출될 수 있다. */
    public interface StatusListener {
        void onStatus(String text, boolean connected);
    }

    private static final String TAG = "WebSocketManager";

    // 재연결 간격 — 끊길 때마다 두 배씩 늘리되 상한을 둔다
    private static final long RETRY_BASE_MS = 1000;
    private static final long RETRY_MAX_MS = 15000;

    // OkHttp 콜백(백그라운드)에서 null로 바꾸고 스캔 콜백(다른 스레드)에서 읽으므로 volatile.
    // 아니면 죽은 소켓 참조가 다른 스레드에 계속 보일 수 있다.
    private volatile WebSocket webSocket;
    private String serverUrl;
    private OkHttpClient client;
    private JSONObject currentMeta = null;
    private MessageListener messageListener;
    private StatusListener statusListener;

    // connect()를 부른 뒤 disconnect() 전까지는 계속 붙어 있어야 한다는 의도.
    // 이 값이 true인 동안에는 끊겨도 자동으로 다시 붙는다.
    private volatile boolean shouldConnect = false;
    private int retryCount = 0;
    private final android.os.Handler retryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    public WebSocketManager(String serverUrl) {
        this.serverUrl = serverUrl;
        this.client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setSurveyMeta(JSONObject meta) {
        this.currentMeta = meta;
    }
    public void markEvent(String event) {
        if (currentMeta != null) {
            try {
                currentMeta.put("event", event);
            } catch (JSONException e) {
                Log.e(TAG, "이벤트 마킹 실패", e);
            }
        }
    }

    public void clearSurveyMeta() {
        this.currentMeta = null;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    private void notifyStatus(String text, boolean connected) {
        StatusListener l = statusListener;
        if (l != null) l.onStatus(text, connected);
    }

    public void connect() {
        shouldConnect = true;
        retryCount = 0;
        openSocket();
    }

    private void openSocket() {
        if (!shouldConnect) return;

        notifyStatus("연결 시도 중...", false);
        Request request = new Request.Builder().url(serverUrl).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                Log.d(TAG, "연결 성공");
                retryCount = 0;
                notifyStatus("연결됨", true);
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                Log.d(TAG, "서버 메시지 : " + text);
                MessageListener listener = messageListener;
                if (listener != null) listener.onServerMessage(text);
            }

            @Override
            public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                Log.d(TAG, "연결 종료 중 : " + reason);
            }

            @Override
            public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                Log.d(TAG, "연결 종료됨 : " + reason);
                scheduleReconnect("연결 끊김");
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, @Nullable Response response) {
                // 예전에는 로그만 찍고 끝냈다. 그러면 webSocket 참조는 남아있지만 죽은 객체라
                // 이후 send()가 계속 false만 반환하면서 아무 표시 없이 전송이 영구히 멈춘다.
                Log.e(TAG, "연결 실패 : " + t.getMessage());
                scheduleReconnect("연결 실패");
            }
        });
    }

    /** 끊긴 소켓을 버리고, 계속 붙어 있어야 하는 상태면 간격을 늘려가며 다시 시도한다. */
    private void scheduleReconnect(String reason) {
        webSocket = null;                 // 죽은 소켓으로 계속 보내지 않도록 확실히 버린다
        if (!shouldConnect) {
            notifyStatus("연결 안 됨", false);
            return;
        }

        long delay = Math.min(RETRY_BASE_MS * (1L << Math.min(retryCount, 4)), RETRY_MAX_MS);
        retryCount++;
        notifyStatus(reason + " — " + (delay / 1000) + "초 후 재연결", false);
        Log.d(TAG, "재연결 예약: " + delay + "ms 후 (" + retryCount + "번째)");

        retryHandler.removeCallbacksAndMessages(null);
        retryHandler.postDelayed(this::openSocket, delay);
    }

    public boolean send(HashMap<String, Object> map) {

        if (webSocket != null) {
            return webSocket.send(buildJson(map));
        }
        return false;
    }

    /**
     * RSSI 데이터가 아닌 제어용 JSON(측정 시작/종료 등)을 그대로 전송한다.
     * RSSI 전송(send)과 달리 bleRssiMap 병합이나 timestamp 자동 추가를 하지 않고,
     * 호출한 쪽이 만든 JSON을 손대지 않고 보낸다.
     */
    public boolean sendControl(JSONObject payload) {
        if (webSocket == null || payload == null) {
            Log.w(TAG, "제어 메시지 전송 실패 (연결 없음): " + payload);
            return false;
        }
        Log.d(TAG, "제어 메시지 전송: " + payload);
        return webSocket.send(payload.toString());
    }

    public boolean isConnected() {
        return webSocket != null;
    }

    public void disconnect() {
        // 의도적으로 끊는 경우이므로 자동 재연결을 먼저 꺼야 한다.
        // (안 그러면 onClosed에서 곧바로 다시 붙어버림)
        shouldConnect = false;
        retryHandler.removeCallbacksAndMessages(null);

        if (webSocket != null) {
            webSocket.close(1000, "정상 종료");
            webSocket = null;
        }
        notifyStatus("연결 안 됨", false);
    }

    private String buildJson(HashMap<String, Object> map) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("timestamp", System.currentTimeMillis());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
            if (currentMeta != null) {          // ← 이 3줄 추가
                obj.put("meta", currentMeta);
            }
        } catch (JSONException e) {
            Log.e("error", "JSON 생성 실패", e);
        }
        Log.d("json", obj.toString());
        return obj.toString();
    }
}