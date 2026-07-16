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

    private static final String TAG = "WebSocketManager";
    private WebSocket webSocket;
    private String serverUrl;
    private OkHttpClient client;

    public WebSocketManager(String serverUrl) {
        this.serverUrl = serverUrl;
        this.client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
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
        } catch (JSONException e) {
            Log.e("error", "JSON 생성 실패", e);
        }
        Log.d("json", obj.toString());
        return obj.toString();
    }
}