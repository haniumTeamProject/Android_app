package org.mcsmtp.blescanner.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 목적지를 말로 받는 음성 인식 래퍼.
 *
 * 받아적은 문자열을 그대로 서버로 올려보내는 것이 전부다. 어느 장소인지 판단하는 일은
 * 서버가 한다(app/ws/llm_matcher.py). 그래야 별칭이나 판정 기준을 고쳐도
 * 앱을 다시 빌드하지 않아도 된다.
 *
 * ── 주의해서 만든 부분 ─────────────────────────────────────────────
 *
 * **메인 스레드 전용.** SpeechRecognizer는 만든 스레드에서만 조작할 수 있다.
 * 웹소켓 수신은 백그라운드에서 오므로, 서버 응답을 받아 다시 듣기를 시작할 때
 * 반드시 메인으로 넘어와야 한다. 그래서 listen()이 알아서 메인으로 post한다.
 *
 * **TTS와 겹치면 안 된다.** 안내를 읽는 도중에 마이크를 켜면 그 소리를 그대로
 * 받아적는다. 그래서 되묻기 흐름에서는 SpeechGuide.speak(text, done)의
 * done 안에서 listen()을 부른다.
 *
 * **Android 11+ 는 매니페스트에 queries가 있어야 한다.** 없으면 음성 인식기가
 * 깔려 있어도 isAvailable()이 false가 나온다. AndroidManifest.xml 참고.
 */
public class VoiceInput {

    private static final String TAG = "VoiceInput";

    /** 결과는 전부 메인 스레드에서 불린다. */
    public interface Listener {
        /** 받아적기 성공. */
        void onHeard(String text);

        /** 실패. message는 사용자에게 보여줄 수 있는 한국어 문장. */
        void onFailed(String message);

        /** 듣기 시작/끝 — 버튼 상태나 안내 문구를 바꾸는 용도. */
        void onListeningChanged(boolean listening);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context appContext;
    private SpeechRecognizer recognizer;
    private Listener listener;
    private boolean listening = false;

    // 결과와 오류가 둘 다 오는 경우가 있어서, 한 번의 듣기에 콜백이 두 번 가지 않게 막는다
    private boolean delivered = false;

    public void init(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isListening() {
        return listening;
    }

    /** 이 기기에서 음성 인식을 쓸 수 있는지. */
    public boolean isAvailable() {
        return appContext != null && SpeechRecognizer.isRecognitionAvailable(appContext);
    }

    /** 듣기 시작. 어느 스레드에서 불러도 안전하다. */
    public void listen() {
        mainHandler.post(this::listenOnMain);
    }

    private void listenOnMain() {
        if (appContext == null) {
            fail("음성 인식을 준비하지 못했습니다.");
            return;
        }
        if (!isAvailable()) {
            fail("이 기기에서 음성 인식을 쓸 수 없습니다.");
            return;
        }
        if (listening) {
            Log.d(TAG, "이미 듣는 중이라 무시");
            return;
        }

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
            recognizer.setRecognitionListener(new Callback());
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.getPackageName());
        // 여러 후보를 받아두면 1순위가 엉뚱할 때 대비가 된다 (지금은 1순위만 쓴다)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        delivered = false;
        listening = true;
        notifyListening(true);
        try {
            recognizer.startListening(intent);
        } catch (SecurityException e) {
            // 마이크 권한이 없을 때 여기로 온다
            listening = false;
            notifyListening(false);
            fail("마이크 권한이 필요합니다.");
        }
    }

    /** 말이 끝났다고 직접 알려줄 때 (버튼을 다시 누른 경우 등). */
    public void stop() {
        mainHandler.post(() -> {
            if (recognizer != null && listening) recognizer.stopListening();
        });
    }

    public void cancel() {
        mainHandler.post(() -> {
            if (recognizer != null) recognizer.cancel();
            listening = false;
            notifyListening(false);
        });
    }

    public void shutdown() {
        mainHandler.post(() -> {
            if (recognizer != null) {
                recognizer.destroy();
                recognizer = null;
            }
            listening = false;
        });
    }

    private void notifyListening(boolean value) {
        Listener l = listener;
        if (l != null) l.onListeningChanged(value);
    }

    private void fail(String message) {
        if (delivered) return;
        delivered = true;
        Listener l = listener;
        if (l != null) l.onFailed(message);
    }

    private void deliver(String text) {
        if (delivered) return;
        delivered = true;
        Listener l = listener;
        if (l != null) l.onHeard(text);
    }

    private class Callback implements RecognitionListener {

        @Override public void onReadyForSpeech(Bundle params) {
            Log.d(TAG, "말씀하세요");
        }

        @Override public void onBeginningOfSpeech() { }

        @Override public void onRmsChanged(float rmsdB) { }

        @Override public void onBufferReceived(byte[] buffer) { }

        @Override public void onEndOfSpeech() {
            // 소리는 끝났지만 결과는 아직이다. listening 표시는 결과가 올 때까지 유지한다.
            Log.d(TAG, "말 끝남, 인식 중");
        }

        @Override public void onResults(Bundle results) {
            listening = false;
            notifyListening(false);

            ArrayList<String> list =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) {
                fail("잘 못 들었습니다. 다시 말씀해 주세요.");
                return;
            }
            String text = list.get(0).trim();
            Log.d(TAG, "받아적음: " + text + " (후보 " + list.size() + "개)");
            deliver(text);
        }

        @Override public void onPartialResults(Bundle partialResults) { }

        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onError(int error) {
            listening = false;
            notifyListening(false);
            Log.w(TAG, "음성 인식 오류: " + error);
            fail(describe(error));
        }

        // 오류 코드를 사용자가 들어서 뭘 해야 할지 알 수 있는 문장으로 바꾼다.
        // 시각장애인이 쓰는 도구라 "오류 7" 같은 건 아무 도움이 안 된다.
        private String describe(int error) {
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    return "잘 못 들었습니다. 다시 말씀해 주세요.";
                case SpeechRecognizer.ERROR_AUDIO:
                    return "마이크를 쓸 수 없습니다.";
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    return "마이크 권한이 필요합니다.";
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    return "네트워크에 연결되지 않아 음성 인식을 할 수 없습니다.";
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    return "음성 인식이 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요.";
                case SpeechRecognizer.ERROR_CLIENT:
                    return "음성 인식을 시작하지 못했습니다.";
                default:
                    return "음성 인식에 실패했습니다. 다시 시도해 주세요.";
            }
        }
    }

    /** 사용자가 취소로 볼 만한 오류인지 (다시 듣기를 자동으로 걸지 판단할 때 쓴다). */
    public static boolean isRetryable(String message) {
        return message != null && message.startsWith("잘 못 들었");
    }

    /** 로케일 표시용 (디버깅 로그에서 어떤 언어로 돌았는지 확인). */
    public static String languageTag() {
        return Locale.KOREAN.toLanguageTag();
    }
}
