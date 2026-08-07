package org.mcsmtp.blescanner.speech;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * 서버가 내려준 안내 문장을 음성으로 읽어주는 TTS 래퍼.
 *
 * 웹소켓 수신은 백그라운드 스레드에서 일어나므로 speak() 호출을 메인 스레드로 넘긴다.
 * 또 TTS 엔진은 초기화가 비동기라, 준비되기 전에 들어온 문장은 마지막 하나만 보관했다가
 * 준비된 직후에 읽어준다(오래된 안내를 몰아서 읽으면 오히려 방해되므로 큐로 쌓지 않는다).
 */
public class SpeechGuide {

    private static final String TAG = "SpeechGuide";
    private static final String UTTERANCE_ID = "guide";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private boolean ready = false;
    private boolean enabled = true;
    private String pendingText = null;

    public void init(Context context) {
        if (tts != null) return;

        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS 초기화 실패: status=" + status);
                return;
            }

            int result = tts.setLanguage(Locale.KOREAN);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 한국어 음성 데이터가 없으면 기기 기본 언어로라도 읽게 둔다 (조용히 실패하지 않도록 로그)
                Log.w(TAG, "한국어 TTS를 쓸 수 없어 기본 언어로 진행합니다");
            }

            ready = true;
            if (pendingText != null) {
                String text = pendingText;
                pendingText = null;
                speakNow(text);
            }
        });
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) stop();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 어느 스레드에서 불러도 안전하다. */
    public void speak(String text) {
        if (!enabled || text == null || text.trim().isEmpty()) return;
        mainHandler.post(() -> {
            if (!ready) {
                pendingText = text;   // 초기화 중이면 마지막 것만 들고 있다가 준비되면 읽음
                return;
            }
            speakNow(text);
        });
    }

    // 안내는 최신 것이 중요하므로 QUEUE_FLUSH로 이전 발화를 끊고 새로 읽는다
    private void speakNow(String text) {
        if (tts == null) return;
        Log.d(TAG, "안내 음성: " + text);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID);
    }

    public void stop() {
        if (tts != null) tts.stop();
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        ready = false;
        pendingText = null;
    }
}
