package com.example.hakshev;

import android.os.Bundle;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** PIN screen. Deliberately not a BaseActivity, so it never re-triggers itself. */
public class LockActivity extends AppCompatActivity {
    private EditText etPin;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_lock);
        etPin = findViewById(R.id.etPin);
        findViewById(R.id.btnUnlock).setOnClickListener(v -> tryUnlock());
        etPin.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryUnlock();
                return true;
            }
            return false;
        });
        // Back must not skip the lock: send the app to the background instead.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });
    }

    private void tryUnlock() {
        if (Prefs.checkPin(this, etPin.getText().toString())) {
            BaseActivity.markUnlocked();
            finish();
        } else {
            etPin.setText("");
            Ui.toast(this, R.string.wrong_pin);
        }
    }
}
