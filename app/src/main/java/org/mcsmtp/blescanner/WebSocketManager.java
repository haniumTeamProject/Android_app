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

    private static final String TAG = "WebSocketManager";
    private WebSocket webSocket;
    private String serverUrl;
    private OkHttpClient client;
    private JSONObject currentMeta = null;
    private MessageListener messageListener;
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

    public void connect() {
        Request request = new Request.Builder().url(serverUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG,"연결 성공");
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                Log.d(TAG, "서버 메시지 : " + text);
                MessageListener listener = messageListener;
                if (listener != null) listener.onServerMessage(text);
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Log.d(TAG, "연결 종료 중 : " + reason);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Log.e(TAG, "연결 실패 : " + t.getMessage());
            }
        });
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
        if (webSocket != null) {
            webSocket.close(1000, "정상 종료");
            webSocket = null;
        }
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