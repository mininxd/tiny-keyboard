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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
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
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class SoftKeyboard extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {

    private static final String PREFS_NAME = "tiny_keyboard_prefs";
    private static final String PREF_HAPTIC = "haptic_feedback";
    private static final String PREF_VIBRATE_POWER = "vibrate_power";
    private static final String PREF_HIGH_CONTRAST = "high_contrast";
    private static final String PREF_HEIGHT_SCALE = "height_scale";
    private static final String PREF_THEME = "keyboard_theme";

    private InputMethodManager mInputMethodManager;
    private KeyboardView mInputView;
    private android.graphics.Insets mInsets;
    private Vibrator mVibrator;

    private int mLastDisplayWidth;
    private int mLastDisplayHeight;
    private boolean mCapsLock;
    private long mLastShiftTime;
    private boolean mHapticEnabled = true;
    private int mVibratePower = 50;
    private boolean mHighContrast = true;
    private int mLastPressedKey = 0;
    
    private LatinKeyboard mSymbolsKeyboard;
    private LatinKeyboard mSymbolsShiftedKeyboard;
    private LatinKeyboard mQwertyKeyboard;
    private LatinKeyboard mCurKeyboard;

    private PopupWindow mDotPopup;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mShowDotPopupRunnable;

    @Override public void onCreate() {
        super.onCreate();
        mInputMethodManager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mHapticEnabled = prefs.getBoolean(PREF_HAPTIC, true);
        mVibratePower = prefs.getInt(PREF_VIBRATE_POWER, 50);
        mHighContrast = prefs.getBoolean(PREF_HIGH_CONTRAST, true);
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
        float scale = getHeightScale();
        int displayHeight = (int) (baseHeight * scale);

        if (mQwertyKeyboard != null) {
            // Configuration changes can happen after the keyboard gets recreated,
            // so we need to be able to re-build the keyboards if the available
            // space has changed.
            if (displayWidth == mLastDisplayWidth && displayHeight == mLastDisplayHeight) return;
            mLastDisplayWidth = displayWidth;
            mLastDisplayHeight = displayHeight;
        }

        boolean wasSymbols = (mCurKeyboard == mSymbolsKeyboard);
        boolean wasSymbolsShifted = (mCurKeyboard == mSymbolsShiftedKeyboard);

        mQwertyKeyboard = new LatinKeyboard(displayContext, R.xml.qwerty, 0, displayWidth, displayHeight, scale);
        mSymbolsKeyboard = new LatinKeyboard(displayContext, R.xml.symbols, 0, displayWidth, displayHeight, scale);
        mSymbolsShiftedKeyboard = new LatinKeyboard(displayContext, R.xml.symbols_shift, 0, displayWidth, displayHeight, scale);

        if (wasSymbolsShifted) {
            mCurKeyboard = mSymbolsShiftedKeyboard;
        } else if (wasSymbols) {
            mCurKeyboard = mSymbolsKeyboard;
        } else {
            mCurKeyboard = mQwertyKeyboard;
        }
    }

    @Override public View onCreateInputView() {
        Context context = getThemedContext();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String theme = prefs.getString(PREF_THEME, "auto");
        boolean highContrast = prefs.getBoolean(PREF_HIGH_CONTRAST, true);

        int layoutRes;
        if ("legacy".equals(theme)) {
            layoutRes = R.layout.input_legacy;
        } else if (highContrast) {
            layoutRes = R.layout.input_contrast;
        } else {
            layoutRes = R.layout.input;
        }

        mInputView = (KeyboardView) LayoutInflater.from(context).inflate(layoutRes, null);
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
                            float threshold = 30 * v.getResources().getDisplayMetrics().density;
                            if (dy < -threshold) {
                                cancelDotPopupTimer();
                                dismissDotPopup();
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
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        cancelDotPopupTimer();
                        dismissDotPopup();
                        break;
                }
                return false;
            }
        });
        setLatinKeyboard(mCurKeyboard != null ? mCurKeyboard : mQwertyKeyboard);
        return mInputView;
    }

    private void setLayoutParams(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mInsets != null) {
            // Only apply bottom insets to avoid overlapping gesture pill/nav bar.
            // Do NOT apply left/right insets as they cause unwanted horizontal shrinking.
            view.setPadding(0, 0, 0, mInsets.bottom);
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
        cancelDotPopupTimer();
        dismissDotPopup();
        mCurKeyboard = mQwertyKeyboard;
        if (mInputView != null) {
            mInputView.closing();
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        cancelDotPopupTimer();
        dismissDotPopup();
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
            cancelDotPopupTimer();
            dismissDotPopup();
            mLastPressedKey = 0;
            showSettingsDialog();
        }
    }

    private void vibrate(int power) {
        if (!mHapticEnabled || power <= 0) return;
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            if (mInputView != null) {
                mInputView.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                );
            }
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (mVibrator.hasAmplitudeControl()) {
                    int amplitude = Math.max(1, Math.min(255, (int) (power * 255.0f / 100.0f)));
                    long duration = 15 + (long) (power * 20.0f / 100.0f);
                    mVibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
                } else {
                    long duration = Math.max(1, (long) (power * 50.0f / 100.0f));
                    mVibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                long duration = Math.max(1, (long) (power * 50.0f / 100.0f));
                mVibrator.vibrate(duration);
            }
        } catch (Exception e) {
            if (mInputView != null) {
                mInputView.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                );
            }
        }
    }
    
    public void onPress(int primaryCode) {
        mLastPressedKey = primaryCode;
        if (primaryCode == 46) {
            cancelDotPopupTimer();
            mShowDotPopupRunnable = () -> showDotPopup();
            mHandler.postDelayed(mShowDotPopupRunnable, 300);
        }
        vibrate(mVibratePower);
    }
    
    public void onRelease(int primaryCode) {
        if (mLastPressedKey == primaryCode) {
            mLastPressedKey = 0;
        }
        if (primaryCode == 46) {
            cancelDotPopupTimer();
            dismissDotPopup();
        }
    }

    private void cancelDotPopupTimer() {
        if (mShowDotPopupRunnable != null) {
            mHandler.removeCallbacks(mShowDotPopupRunnable);
            mShowDotPopupRunnable = null;
        }
    }

    private void showDotPopup() {
        if (mInputView == null || mInputView.getWindowToken() == null) return;
        dismissDotPopup();

        Keyboard.Key dotKey = null;
        if (mCurKeyboard != null && mCurKeyboard.getKeys() != null) {
            for (Keyboard.Key k : mCurKeyboard.getKeys()) {
                if (k.codes != null && k.codes.length > 0 && k.codes[0] == 46) {
                    dotKey = k;
                    break;
                }
            }
        }
        if (dotKey == null) return;

        Context context = getThemedContext();
        float density = context.getResources().getDisplayMetrics().density;

        TextView tv = new TextView(context);
        tv.setText("⚙ Settings  ▲");
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);

        int padH = (int) (12 * density);
        int padV = (int) (8 * density);
        tv.setPadding(padH, padV, padH, padV);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(12 * density);

        String theme = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_THEME, "auto");
        boolean isNight = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "dark".equals(theme) || ("auto".equals(theme) && isNight) || "legacy".equals(theme);

        if (dark) {
            bg.setColor(0xFF2B2D31);
            bg.setStroke((int) (1.5f * density), 0xFF004A77);
            tv.setTextColor(0xFFE3E3E8);
        } else {
            bg.setColor(0xFFFFFFFF);
            bg.setStroke((int) (1.5f * density), 0xFFD3E3FD);
            tv.setTextColor(0xFF1B1B1F);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tv.setElevation(8 * density);
        }
        tv.setBackground(bg);

        tv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int popW = tv.getMeasuredWidth();
        int popH = tv.getMeasuredHeight();

        mDotPopup = new PopupWindow(tv, popW, popH);
        mDotPopup.setClippingEnabled(false);

        int[] loc = new int[2];
        mInputView.getLocationInWindow(loc);
        int posX = loc[0] + dotKey.x + dotKey.width / 2 - popW / 2;
        int posY = loc[1] + dotKey.y - popH - (int) (8 * density);

        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        if (posX + popW > screenW - 10) {
            posX = screenW - popW - 10;
        }
        if (posX < 10) {
            posX = 10;
        }

        try {
            mDotPopup.showAtLocation(mInputView, Gravity.NO_GRAVITY, posX, posY);
            vibrate(mVibratePower);
        } catch (Exception ignored) {}
    }

    private void dismissDotPopup() {
        if (mDotPopup != null) {
            try {
                if (mDotPopup.isShowing()) {
                    mDotPopup.dismiss();
                }
            } catch (Exception ignored) {}
            mDotPopup = null;
        }
    }

    private void showSettingsDialog() {
        if (mInputView == null || mInputView.getWindowToken() == null) {
            return;
        }

        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean currentHaptic = prefs.getBoolean(PREF_HAPTIC, true);
        int currentVibratePower = prefs.getInt(PREF_VIBRATE_POWER, 50);
        boolean currentHighContrast = prefs.getBoolean(PREF_HIGH_CONTRAST, true);
        int currentHeight = prefs.getInt(PREF_HEIGHT_SCALE, 100);
        final String currentTheme = prefs.getString(PREF_THEME, "auto");

        Context context = getDisplayContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Tiny Keyboard Settings");

        ScrollView scrollView = new ScrollView(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        scrollView.addView(layout);

        final CheckBox hapticCheck = new CheckBox(context);
        hapticCheck.setText("Haptic feedback");
        hapticCheck.setChecked(currentHaptic);
        layout.addView(hapticCheck);

        final TextView vibrateLabel = new TextView(context);
        vibrateLabel.setText("Feedback power: " + currentVibratePower + "%");
        vibrateLabel.setPadding(0, pad / 4, 0, pad / 4);
        layout.addView(vibrateLabel);

        final SeekBar vibrateBar = new SeekBar(context);
        vibrateBar.setMax(99); // 1% to 100%
        vibrateBar.setProgress(currentVibratePower - 1);
        vibrateBar.setEnabled(currentHaptic);
        vibrateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int p = progress + 1;
                vibrateLabel.setText("Feedback power: " + p + "%");
                if (fromUser && hapticCheck.isChecked()) {
                    vibrate(p);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(vibrateBar);

        hapticCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            vibrateBar.setEnabled(isChecked);
            vibrateLabel.setAlpha(isChecked ? 1.0f : 0.5f);
        });
        if (!currentHaptic) {
            vibrateLabel.setAlpha(0.5f);
        }

        final CheckBox contrastCheck = new CheckBox(context);
        contrastCheck.setText("High contrast");
        contrastCheck.setChecked(currentHighContrast);
        contrastCheck.setPadding(0, pad / 4, 0, pad / 4);
        layout.addView(contrastCheck);

        final TextView themeLabel = new TextView(context);
        themeLabel.setText("Theme");
        themeLabel.setPadding(0, pad / 2, 0, pad / 4);
        layout.addView(themeLabel);

        final RadioGroup themeGroup = new RadioGroup(context);
        themeGroup.setOrientation(RadioGroup.VERTICAL);

        final RadioButton legacyBtn = new RadioButton(context);
        legacyBtn.setId(1);
        legacyBtn.setText("Legacy");
        themeGroup.addView(legacyBtn);

        final RadioButton lightBtn = new RadioButton(context);
        lightBtn.setId(2);
        lightBtn.setText("Light");
        themeGroup.addView(lightBtn);

        final RadioButton darkBtn = new RadioButton(context);
        darkBtn.setId(3);
        darkBtn.setText("Dark");
        themeGroup.addView(darkBtn);

        final RadioButton autoBtn = new RadioButton(context);
        autoBtn.setId(4);
        autoBtn.setText("Auto");
        themeGroup.addView(autoBtn);

        if ("legacy".equals(currentTheme)) {
            legacyBtn.setChecked(true);
        } else if ("light".equals(currentTheme)) {
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

        builder.setView(scrollView);
        builder.setPositiveButton("OK", (dialog, which) -> {
            boolean haptic = hapticCheck.isChecked();
            int vibratePower = 1 + vibrateBar.getProgress();
            boolean highContrast = contrastCheck.isChecked();
            int height = 70 + heightBar.getProgress();
            int checkedThemeId = themeGroup.getCheckedRadioButtonId();
            String selectedTheme = "auto";
            if (checkedThemeId == 1) {
                selectedTheme = "legacy";
            } else if (checkedThemeId == 2) {
                selectedTheme = "light";
            } else if (checkedThemeId == 3) {
                selectedTheme = "dark";
            } else if (checkedThemeId == 4) {
                selectedTheme = "auto";
            }

            prefs.edit()
                .putBoolean(PREF_HAPTIC, haptic)
                .putInt(PREF_VIBRATE_POWER, vibratePower)
                .putBoolean(PREF_HIGH_CONTRAST, highContrast)
                .putInt(PREF_HEIGHT_SCALE, height)
                .putString(PREF_THEME, selectedTheme)
                .apply();
            mHapticEnabled = haptic;
            mVibratePower = vibratePower;
            mHighContrast = highContrast;
            mLastDisplayWidth = 0;
            mLastDisplayHeight = 0;
            onInitializeInterface();
            setInputView(onCreateInputView());
            updateInputViewShown();
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
