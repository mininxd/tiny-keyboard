/*
 * Copyright (C) 2008-2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.tinykeyboard.inputmethod;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Build;
import android.os.IBinder;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

public class SoftKeyboard extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {

    private static final String PREFS_NAME = "tiny_keyboard_prefs";
    private static final String PREF_HAPTIC = "haptic_feedback";
    private static final String PREF_HEIGHT_SCALE = "height_scale";
    private static final String PREF_THEME = "keyboard_theme";

    private InputMethodManager mInputMethodManager;
    private KeyboardView mInputView;
    private static android.graphics.Insets mInsets;

    private int mLastDisplayWidth;
    private int mLastDisplayHeight;
    private boolean mCapsLock;
    private long mLastShiftTime;
    private boolean mHapticEnabled = true;
    private int mLastPressedKey = 0;
    
    private LatinKeyboard mSymbolsKeyboard;
    private LatinKeyboard mSymbolsShiftedKeyboard;
    private LatinKeyboard mQwertyKeyboard;
    
    private LatinKeyboard mCurKeyboard;

    @Override public void onCreate() {
        super.onCreate();
        mInputMethodManager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mHapticEnabled = prefs.getBoolean(PREF_HAPTIC, true);
    }

    private float getHeightScale() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_HEIGHT_SCALE, 100) / 100.0f;
    }

    Context getDisplayContext() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            // createDisplayContext is not available.
            return this;
        }
        // TODO (b/133825283): Non-activity components Resources / DisplayMetrics update when
        //  moving to external display.
        // An issue in Q that non-activity components Resources / DisplayMetrics in
        // Context doesn't well updated when the IME window moving to external display.
        // Currently we do a workaround is to create new display context directly and re-init
        // keyboard layout with this context.
        final WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        return createDisplayContext(wm.getDefaultDisplay());
    }

    Context getThemedContext() {
        Context context = getDisplayContext();
        String theme = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_THEME, "auto");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Configuration conf = new Configuration(context.getResources().getConfiguration());
            if ("light".equals(theme)) {
                conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
                return context.createConfigurationContext(conf);
            } else if ("dark".equals(theme)) {
                conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
                return context.createConfigurationContext(conf);
            }
        }
        return context;
    }

    @Override public void onInitializeInterface() {
        final Context displayContext = getThemedContext();

        int displayWidth = getMaxWidth();
        int baseHeight = displayContext.getResources().getDisplayMetrics().heightPixels;
        int displayHeight = (int) (baseHeight * getHeightScale());

        if (mQwertyKeyboard != null) {
            // Configuration changes can happen after the keyboard gets recreated,
            // so we need to be able to re-build the keyboards if the available
            // space has changed.
            if (displayWidth == mLastDisplayWidth && displayHeight == mLastDisplayHeight) return;
            mLastDisplayWidth = displayWidth;
            mLastDisplayHeight = displayHeight;
        }
        mQwertyKeyboard = new LatinKeyboard(displayContext, R.xml.qwerty, 0, displayWidth, displayHeight);
        mSymbolsKeyboard = new LatinKeyboard(displayContext, R.xml.symbols, 0, displayWidth, displayHeight);
        mSymbolsShiftedKeyboard = new LatinKeyboard(displayContext, R.xml.symbols_shift, 0, displayWidth, displayHeight);
    }

    @Override public View onCreateInputView() {
        Context context = getThemedContext();
        mInputView = (KeyboardView) LayoutInflater.from(context).inflate(R.layout.input, null);
        mInputView.setOnKeyboardActionListener(this);
        mInputView.setPreviewEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setLayoutParams(mInputView);
            mInputView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                mInsets = windowInsets.getInsets(WindowInsets.Type.systemBars());
                setLayoutParams(mInputView);
                return WindowInsets.CONSUMED;
            });
        }
        mInputView.setOnTouchListener(new View.OnTouchListener() {
            private float mDownY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownY = event.getY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (mLastPressedKey == 46) {
                            float dy = event.getY() - mDownY;
                            float threshold = 35 * v.getResources().getDisplayMetrics().density;
                            if (dy < -threshold) {
                                mLastPressedKey = 0;
                                showSettingsDialog();
                                MotionEvent cancelEvent = MotionEvent.obtain(event);
                                cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
                                v.onTouchEvent(cancelEvent);
                                cancelEvent.recycle();
                                return true;
                            }
                        }
                        break;
                }
                return false;
            }
        });
        setLatinKeyboard(mQwertyKeyboard);
        return mInputView;
    }

    private void setLayoutParams(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mInsets != null) {
            view.setPadding(mInsets.left, 0, mInsets.right, mInsets.bottom);
        }
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    private void setLatinKeyboard(LatinKeyboard nextKeyboard) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
            final boolean shouldSupportLanguageSwitchKey = mInputMethodManager.shouldOfferSwitchingToNextInputMethod(getToken());
            nextKeyboard.setLanguageSwitchKeyVisibility(shouldSupportLanguageSwitchKey);
        }
        mInputView.setKeyboard(nextKeyboard);
    }

    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        
        // We are now going to initialize our state based on the type of
        // text being edited.
        switch (attribute.inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_DATETIME:
            case InputType.TYPE_CLASS_PHONE:
                // Numbers and dates default to the symbols keyboard, with
                // no extra features.
                mCurKeyboard = mSymbolsKeyboard;
                break;
                
            default:
                // For all unknown input types, default to the alphabetic
                // keyboard with no special features.
                mCurKeyboard = mQwertyKeyboard;
                updateShiftKeyState(attribute);
        }
        
        // Update the label on the enter key, depending on what the application
        // says it will do.
        mCurKeyboard.setImeOptions(getResources(), attribute.imeOptions);
    }

    @Override public void onFinishInput() {
        super.onFinishInput();
        
        mCurKeyboard = mQwertyKeyboard;
        if (mInputView != null) {
            mInputView.closing();
        }
    }
    
    @Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        // Apply the selected keyboard to the input view.
        setLatinKeyboard(mCurKeyboard);
    }

    private void updateShiftKeyState(EditorInfo attr) {
        if (attr != null && mInputView != null && mQwertyKeyboard == mInputView.getKeyboard()) {
            int caps = 0;
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && ei.inputType != InputType.TYPE_NULL) {
                caps = getCurrentInputConnection().getCursorCapsMode(attr.inputType);
            }
            mInputView.setShifted(mCapsLock || caps != 0);
        }
    }

    private void keyDownUp(int keyEventCode) {
        getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode));
        getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyEventCode));
    }

    // Implementation of KeyboardViewListener

    public void onKey(int primaryCode, int[] keyCodes) {
        if (primaryCode == Keyboard.KEYCODE_DONE) {
            keyDownUp(KeyEvent.KEYCODE_ENTER);
        } else if (primaryCode == Keyboard.KEYCODE_DELETE) {
            handleBackspace();
        } else if (primaryCode == Keyboard.KEYCODE_SHIFT) {
            handleShift();
        } else if (primaryCode == LatinKeyboard.KEYCODE_LANGUAGE_SWITCH) {
            handleLanguageSwitch();
        } else if (primaryCode == Keyboard.KEYCODE_MODE_CHANGE && mInputView != null) {
            Keyboard current = mInputView.getKeyboard();
            if (current == mSymbolsKeyboard || current == mSymbolsShiftedKeyboard) {
                setLatinKeyboard(mQwertyKeyboard);
            } else {
                setLatinKeyboard(mSymbolsKeyboard);
                mSymbolsKeyboard.setShifted(false);
            }
        } else {
            handleCharacter(primaryCode);
        }
    }

    public void onText(CharSequence text) {
    }
    
    private void handleBackspace() {
        keyDownUp(KeyEvent.KEYCODE_DEL);
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    private void handleShift() {
        if (mInputView == null) {
            return;
        }
        
        Keyboard currentKeyboard = mInputView.getKeyboard();
        if (currentKeyboard == mQwertyKeyboard) {
            // Alphabet keyboard
            checkToggleCapsLock();
            mInputView.setShifted(mCapsLock || !mInputView.isShifted());
        } else if (currentKeyboard == mSymbolsKeyboard) {
            mSymbolsKeyboard.setShifted(true);
            setLatinKeyboard(mSymbolsShiftedKeyboard);
            mSymbolsShiftedKeyboard.setShifted(true);
        } else if (currentKeyboard == mSymbolsShiftedKeyboard) {
            mSymbolsShiftedKeyboard.setShifted(false);
            setLatinKeyboard(mSymbolsKeyboard);
            mSymbolsKeyboard.setShifted(false);
        }
    }
    
    private void handleCharacter(int primaryCode) {
        if (isInputViewShown()) {
            if (mInputView.isShifted()) {
                primaryCode = Character.toUpperCase(primaryCode);
            }
        }
        getCurrentInputConnection().commitText(String.valueOf((char) primaryCode), 1);
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    private IBinder getToken() {
        final Dialog dialog = getWindow();
        if (dialog == null) {
            return null;
        }
        final Window window = dialog.getWindow();
        if (window == null) {
            return null;
        }
        return window.getAttributes().token;
    }

    private void handleLanguageSwitch() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            mInputMethodManager.switchToNextInputMethod(getToken(), false /* onlyCurrentIme */);
        }
    }

    private void checkToggleCapsLock() {
        long now = System.currentTimeMillis();
        if (mLastShiftTime + 500 > now || mCapsLock) {
            mCapsLock = !mCapsLock;
            mLastShiftTime = 0;
        } else {
            mLastShiftTime = now;
        }
    }
    
    public void swipeRight() {
    }
    
    public void swipeLeft() {
    }

    public void swipeDown() {
    }

    public void swipeUp() {
        if (mLastPressedKey == 46) {
            mLastPressedKey = 0;
            showSettingsDialog();
        }
    }
    
    public void onPress(int primaryCode) {
        mLastPressedKey = primaryCode;
        if (mHapticEnabled && mInputView != null) {
            mInputView.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            );
        }
    }
    
    public void onRelease(int primaryCode) {
        if (mLastPressedKey == primaryCode) {
            mLastPressedKey = 0;
        }
    }

    private void showSettingsDialog() {
        if (mInputView == null || mInputView.getWindowToken() == null) {
            return;
        }

        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean currentHaptic = prefs.getBoolean(PREF_HAPTIC, true);
        int currentHeight = prefs.getInt(PREF_HEIGHT_SCALE, 100);
        final String currentTheme = prefs.getString(PREF_THEME, "auto");

        Context context = getDisplayContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Tiny Keyboard Settings");

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final CheckBox hapticCheck = new CheckBox(context);
        hapticCheck.setText("Haptic feedback");
        hapticCheck.setChecked(currentHaptic);
        layout.addView(hapticCheck);

        final TextView themeLabel = new TextView(context);
        themeLabel.setText("Theme");
        themeLabel.setPadding(0, pad / 2, 0, pad / 4);
        layout.addView(themeLabel);

        final RadioGroup themeGroup = new RadioGroup(context);
        themeGroup.setOrientation(RadioGroup.HORIZONTAL);

        final RadioButton autoBtn = new RadioButton(context);
        autoBtn.setId(1);
        autoBtn.setText("Auto");
        themeGroup.addView(autoBtn);

        final RadioButton lightBtn = new RadioButton(context);
        lightBtn.setId(2);
        lightBtn.setText("Light");
        themeGroup.addView(lightBtn);

        final RadioButton darkBtn = new RadioButton(context);
        darkBtn.setId(3);
        darkBtn.setText("Dark");
        themeGroup.addView(darkBtn);

        if ("light".equals(currentTheme)) {
            lightBtn.setChecked(true);
        } else if ("dark".equals(currentTheme)) {
            darkBtn.setChecked(true);
        } else {
            autoBtn.setChecked(true);
        }
        layout.addView(themeGroup);

        final TextView heightLabel = new TextView(context);
        heightLabel.setText("Keyboard height: " + currentHeight + "%");
        heightLabel.setPadding(0, pad / 2, 0, pad / 4);
        layout.addView(heightLabel);

        final SeekBar heightBar = new SeekBar(context);
        heightBar.setMax(60); // 70% to 130%
        heightBar.setProgress(currentHeight - 70);
        heightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                heightLabel.setText("Keyboard height: " + (70 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(heightBar);

        builder.setView(layout);
        builder.setPositiveButton("OK", (dialog, which) -> {
            boolean haptic = hapticCheck.isChecked();
            int height = 70 + heightBar.getProgress();
            int checkedThemeId = themeGroup.getCheckedRadioButtonId();
            String selectedTheme = "auto";
            if (checkedThemeId == 2) {
                selectedTheme = "light";
            } else if (checkedThemeId == 3) {
                selectedTheme = "dark";
            }

            prefs.edit()
                .putBoolean(PREF_HAPTIC, haptic)
                .putInt(PREF_HEIGHT_SCALE, height)
                .putString(PREF_THEME, selectedTheme)
                .apply();
            mHapticEnabled = haptic;
            mLastDisplayWidth = 0;
            mLastDisplayHeight = 0;
            onInitializeInterface();
            setInputView(onCreateInputView());
        });
        builder.setNegativeButton("Cancel", null);

        Dialog dialog = builder.create();
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.token = mInputView.getWindowToken();
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
        dialog.show();
    }
}
