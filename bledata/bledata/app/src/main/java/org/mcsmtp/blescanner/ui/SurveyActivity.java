package org.mcsmtp.blescanner.ui;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.mcsmtp.blescanner.R;
import org.mcsmtp.blescanner.ble.BleScanner;

public class SurveyActivity extends AppCompatActivity {

    private RadioGroup radioMode;
    private View layoutStatic, layoutDynamic;
    private Spinner spinnerDirection, spinnerScenario;
    private TextView textCountdown;
    private CountDownTimer countDownTimer;
    private BleScanner bleScanner;

    private EditText editPointId, editPathId, editDeviceId, editWaypointName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_survey);

        bleScanner = BleScanner.getInstance(this);

        // ↓ 이 블록 다시 추가
        View root = findViewById(R.id.surveyRoot);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        radioMode = findViewById(R.id.radioMode);
        layoutStatic = findViewById(R.id.layoutStatic);
        layoutDynamic = findViewById(R.id.layoutDynamic);
        editPointId = findViewById(R.id.editPointId);
        editPathId = findViewById(R.id.editPathId);
        editDeviceId = findViewById(R.id.editDeviceId);
        spinnerDirection = findViewById(R.id.spinnerDirection);
        spinnerScenario = findViewById(R.id.spinnerScenario);
        textCountdown = findViewById(R.id.textCountdown);
        editWaypointName = findViewById(R.id.editWaypointName);
        // ... 이하 기존 코드 그대로

        spinnerDirection.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"0", "90", "180", "270"}));
        spinnerScenario.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"직선보행", "코너회전", "정지재출발", "혼잡"}));

        radioMode.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isStatic = checkedId == R.id.radioStatic;
            layoutStatic.setVisibility(isStatic ? View.VISIBLE : View.GONE);
            layoutDynamic.setVisibility(isStatic ? View.GONE : View.VISIBLE);
        });

        findViewById(R.id.btnStartSurvey).setOnClickListener(v -> startSurvey());
        findViewById(R.id.btnWaypoint).setOnClickListener(v ->
                bleScanner.markSurveyEvent("WAYPOINT:" + editPointId.getText().toString()));
        findViewById(R.id.btnStopStart).setOnClickListener(v ->
                bleScanner.markSurveyEvent("STOP_START"));
        findViewById(R.id.btnStopEnd).setOnClickListener(v ->
                bleScanner.markSurveyEvent("STOP_END"));
        findViewById(R.id.btnStopSurvey).setOnClickListener(v -> bleScanner.stopSurvey());
        findViewById(R.id.btnWaypoint).setOnClickListener(v ->
                bleScanner.markSurveyEvent("WAYPOINT:" + editWaypointName.getText().toString()));
    }

    private void startSurvey() {
        try {
            JSONObject meta = new JSONObject();
            meta.put("deviceId", editDeviceId.getText().toString());

            if (radioMode.getCheckedRadioButtonId() == R.id.radioStatic) {
                meta.put("mode", "static");
                meta.put("pointId", editPointId.getText().toString());
                meta.put("directionDeg", Integer.parseInt(
                        spinnerDirection.getSelectedItem().toString()));
            } else {
                meta.put("mode", "dynamic");
                meta.put("pathId", editPathId.getText().toString());
                meta.put("scenario", spinnerScenario.getSelectedItem().toString());
            }

            bleScanner.startSurvey(meta);
            Toast.makeText(this, "측정 시작됨", Toast.LENGTH_SHORT).show();

            if (radioMode.getCheckedRadioButtonId() == R.id.radioStatic) {
                startCountdown(30);
            }
        } catch (JSONException | NumberFormatException e) {
            Toast.makeText(this, "입력값을 확인해주세요", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCountdown(int seconds) {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        countDownTimer = new CountDownTimer(seconds * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long secLeft = millisUntilFinished / 1000;
                textCountdown.setText(secLeft + "초");
            }

            @Override
            public void onFinish() {
                textCountdown.setText("완료 ✓");
                Toast.makeText(SurveyActivity.this, "30초 측정 완료! 다음 방향/지점으로 이동하세요", Toast.LENGTH_LONG).show();
            }
        }.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
}