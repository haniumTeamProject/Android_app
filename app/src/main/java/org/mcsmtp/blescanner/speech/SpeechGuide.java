package org.mcsmtp.blescanner.speech;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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
    private Runnable pendingDone = null;

    // 읽기가 끝나면 실행할 것. 목적지 되묻기에서 "안내를 다 읽은 뒤 마이크를 켠다"에 쓴다.
    // 읽는 도중에 마이크를 켜면 TTS 소리를 그대로 받아적어버린다.
    private Runnable onDone = null;

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

            // 콜백은 바인더 스레드에서 오므로 메인으로 넘긴다.
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }

                @Override public void onDone(String utteranceId) {
                    fireDone();
                }

                @Override public void onError(String utteranceId) {
                    // 읽기에 실패해도 다음 단계는 진행시켜야 흐름이 멈추지 않는다
                    fireDone();
                }
            });

            ready = true;
            if (pendingText != null) {
                String text = pendingText;
                Runnable done = pendingDone;
                pendingText = null;
                pendingDone = null;
                speakNow(text, done);
            }
        });
    }

    private void fireDone() {
        mainHandler.post(() -> {
            Runnable r = onDone;
            onDone = null;
            if (r != null) r.run();
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
        speak(text, null);
    }

    /**
     * 다 읽은 뒤 done을 실행한다. done은 메인 스레드에서 불린다.
     *
     * 목적지 되묻기가 이걸 쓴다. "화장실이 2곳 있습니다..."를 읽는 동안 마이크를 켜면
     * 그 소리를 그대로 받아적으므로, 다 읽은 것을 확인하고 나서 켜야 한다.
     * 음성 안내가 꺼져 있거나 읽을 게 없으면 done을 바로 실행한다 —
     * 안 그러면 안내를 끈 사용자는 되묻기 흐름이 통째로 멈춘다.
     */
    public void speak(String text, Runnable done) {
        if (text == null || text.trim().isEmpty() || !enabled) {
            if (done != null) mainHandler.post(done);
            return;
        }
        mainHandler.post(() -> {
            if (!ready) {
                pendingText = text;   // 초기화 중이면 마지막 것만 들고 있다가 준비되면 읽음
                pendingDone = done;
                return;
            }
            speakNow(text, done);
        });
    }

    // 안내는 최신 것이 중요하므로 QUEUE_FLUSH로 이전 발화를 끊고 새로 읽는다
    private void speakNow(String text, Runnable done) {
        if (tts == null) {
            if (done != null) done.run();
            return;
        }
        Log.d(TAG, "안내 음성: " + text);
        onDone = done;
        int rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID);
        if (rc != TextToSpeech.SUCCESS) {
            // speak 자체가 실패하면 onDone 콜백이 영영 안 온다. 흐름이 멈추지 않게 여기서 처리.
            Log.w(TAG, "speak 실패: rc=" + rc);
            fireDone();
        }
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
        pendingDone = null;
        onDone = null;
    }
}
