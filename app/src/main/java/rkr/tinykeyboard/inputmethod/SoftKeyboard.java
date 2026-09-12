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
import android.graphics.Typeface;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class SoftKeyboard extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {

    private static final String PREFS_NAME = "tiny_keyboard_prefs";
    private static final String PREF_SWIPE_CASE = "swipe_case";
    private static final String PREF_HAPTIC = "haptic_feedback";
    private static final String PREF_VIBRATE_DURATION = "vibrate_duration";
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
    private boolean mSwipeCaseEnabled = false;
    private boolean mHapticEnabled = true;
    private int mVibrateDuration = 20; // 5ms to 100ms
    private boolean mHighContrast = true;
    private int mLastPressedKey = 0;
    
    private LatinKeyboard mSymbolsKeyboard;
    private LatinKeyboard mSymbolsShiftedKeyboard;
    private LatinKeyboard mQwertyKeyboard;
    private LatinKeyboard mCurKeyboard;


    @Override public void onCreate() {
        super.onCreate();
        mInputMethodManager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mSwipeCaseEnabled = prefs.getBoolean(PREF_SWIPE_CASE, false);
        mHapticEnabled = prefs.getBoolean(PREF_HAPTIC, true);
        mVibrateDuration = prefs.getInt(PREF_VIBRATE_DURATION, 20);
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
                .getString(PREF_THEME, "legacy");
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
        String theme = prefs.getString(PREF_THEME, "legacy");
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
            private float mDownX;
            private float mDownY;
            private boolean mSwipeHandled;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownX = event.getX();
                        mDownY = event.getY();
                        mSwipeHandled = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (mSwipeHandled) {
                            return true;
                        }
                        float dx = event.getX() - mDownX;
                        float dy = event.getY() - mDownY;
                        float density = v.getResources().getDisplayMetrics().density;

                        if (mLastPressedKey == 46) {
                            float threshold = 30 * density;
                            if (dy < -threshold) {
                                mSwipeHandled = true;
                                mLastPressedKey = 0;
                                showSettingsDialog();
                                cancelTouchOnView(v, event);
                                return true;
                            }
                        } else if (mSwipeCaseEnabled && mCurKeyboard == mQwertyKeyboard
                                && Character.isLetter(mLastPressedKey)) {
                            float threshold = 24 * density;
                            if (Math.abs(dy) > threshold && Math.abs(dy) > Math.abs(dx)) {
                                int key = mLastPressedKey;
                                mSwipeHandled = true;
                                mLastPressedKey = 0;
                                char c = (dy < 0) ? Character.toUpperCase((char) key) : Character.toLowerCase((char) key);
                                InputConnection ic = getCurrentInputConnection();
                                if (ic != null) {
                                    ic.commitText(String.valueOf(c), 1);
                                    updateShiftKeyState(getCurrentInputEditorInfo());
                                }
                                vibrate(mVibrateDuration);
                                cancelTouchOnView(v, event);
                                return true;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (mSwipeHandled) {
                            mSwipeHandled = false;
                            return true;
                        }
                        break;
                }
                return false;
            }
        });
        setLatinKeyboard(mCurKeyboard != null ? mCurKeyboard : mQwertyKeyboard);
        return mInputView;
    }

    private void cancelTouchOnView(View v, MotionEvent event) {
        MotionEvent cancelEvent = MotionEvent.obtain(event);
        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
        v.onTouchEvent(cancelEvent);
        cancelEvent.recycle();
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
        mCurKeyboard = mQwertyKeyboard;
        if (mInputView != null) {
            mInputView.closing();
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
    }
    
    @Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        // Apply the selected keyboard to the input view.
        setLatinKeyboard(mCurKeyboard);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    private void updateShiftKeyState(EditorInfo attr) {
        if (attr == null) {
            attr = getCurrentInputEditorInfo();
        }
        if (attr != null && mInputView != null && mQwertyKeyboard == mInputView.getKeyboard()) {
            int caps = 0;
            InputConnection ic = getCurrentInputConnection();
            if (attr.inputType != InputType.TYPE_NULL && ic != null) {
                int reqModes = attr.inputType;
                if ((attr.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
                    int flags = attr.inputType & (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                            | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                    if (flags == 0) {
                        int variation = attr.inputType & InputType.TYPE_MASK_VARIATION;
                        if (variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                                && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                                && variation != InputType.TYPE_TEXT_VARIATION_URI
                                && variation != InputType.TYPE_TEXT_VARIATION_FILTER) {
                            reqModes |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
                        }
                    }
                }
                caps = ic.getCursorCapsMode(reqModes);
                if (caps == 0 && (reqModes & (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)) != 0) {
                    CharSequence textBefore = ic.getTextBeforeCursor(1, 0);
                    if (textBefore == null || textBefore.length() == 0) {
                        caps = 1;
                    }
                }
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
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            CharSequence selected = ic.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                ic.commitText("", 1);
            } else {
                CharSequence textBefore = ic.getTextBeforeCursor(2, 0);
                if (textBefore != null && textBefore.length() > 0) {
                    if (textBefore.length() >= 2 && Character.isSurrogatePair(textBefore.charAt(0), textBefore.charAt(1))) {
                        ic.deleteSurroundingText(2, 0);
                    } else {
                        ic.deleteSurroundingText(1, 0);
                    }
                } else {
                    keyDownUp(KeyEvent.KEYCODE_DEL);
                }
            }
        } else {
            keyDownUp(KeyEvent.KEYCODE_DEL);
        }
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
        if (mSwipeCaseEnabled && mCurKeyboard == mQwertyKeyboard && Character.isLetter(mLastPressedKey)) {
            int key = mLastPressedKey;
            mLastPressedKey = 0;
            char c = Character.toLowerCase((char) key);
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitText(String.valueOf(c), 1);
                updateShiftKeyState(getCurrentInputEditorInfo());
            }
            vibrate(mVibrateDuration);
        }
    }

    public void swipeUp() {
        if (mLastPressedKey == 46) {
            mLastPressedKey = 0;
            showSettingsDialog();
        } else if (mSwipeCaseEnabled && mCurKeyboard == mQwertyKeyboard && Character.isLetter(mLastPressedKey)) {
            int key = mLastPressedKey;
            mLastPressedKey = 0;
            char c = Character.toUpperCase((char) key);
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitText(String.valueOf(c), 1);
                updateShiftKeyState(getCurrentInputEditorInfo());
            }
            vibrate(mVibrateDuration);
        }
    }

    private Vibrator getVibrator() {
        if (mVibrator == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    android.os.VibratorManager vm = (android.os.VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    if (vm != null) {
                        mVibrator = vm.getDefaultVibrator();
                    }
                } catch (Throwable ignored) {}
            }
            if (mVibrator == null) {
                try {
                    mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                } catch (Throwable ignored) {}
            }
        }
        return mVibrator;
    }

    private void vibrate(long durationMs) {
        if (!mHapticEnabled || durationMs <= 0) return;
        Vibrator v = getVibrator();
        if (v == null || !v.hasVibrator()) {
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
                AudioAttributes audioAttrs = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build();
                VibrationEffect effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE);
                v.vibrate(effect, audioAttrs);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes audioAttrs = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build();
                v.vibrate(durationMs, audioAttrs);
            } else {
                v.vibrate(durationMs);
            }
        } catch (Throwable t) {
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
        vibrate(mVibrateDuration);
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
        boolean currentSwipeCase = prefs.getBoolean(PREF_SWIPE_CASE, false);
        boolean currentHaptic = prefs.getBoolean(PREF_HAPTIC, true);
        int currentVibrateDuration = prefs.getInt(PREF_VIBRATE_DURATION, 20);
        boolean currentHighContrast = prefs.getBoolean(PREF_HIGH_CONTRAST, true);
        int currentHeight = prefs.getInt(PREF_HEIGHT_SCALE, 100);
        final String currentTheme = prefs.getString(PREF_THEME, "legacy");

        Context context = getDisplayContext();
        float density = context.getResources().getDisplayMetrics().density;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Tiny Keyboard Settings");

        ScrollView scrollView = new ScrollView(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (16 * density);
        int padV = (int) (8 * density);
        layout.setPadding(padH, padV, padH, padV);
        scrollView.addView(layout);

        // --- Typing Gestures ---
        final CheckBox swipeCaseCheck = new CheckBox(context);
        swipeCaseCheck.setText("Swipe letter for case (▲ Upper, ▼ Lower)");
        swipeCaseCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        swipeCaseCheck.setChecked(currentSwipeCase);
        layout.addView(swipeCaseCheck);

        // --- High Contrast ---
        final CheckBox contrastCheck = new CheckBox(context);
        contrastCheck.setText("High contrast & key depth");
        contrastCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        contrastCheck.setChecked(currentHighContrast);
        layout.addView(contrastCheck);

        // --- Theme (Compact 2x2 Grid) ---
        final TextView themeLabel = new TextView(context);
        themeLabel.setText("Theme");
        themeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        themeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        themeLabel.setPadding(0, (int) (6 * density), 0, (int) (2 * density));
        layout.addView(themeLabel);

        final RadioButton legacyBtn = new RadioButton(context);
        legacyBtn.setText("Legacy");
        legacyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

        final RadioButton autoBtn = new RadioButton(context);
        autoBtn.setText("Auto");
        autoBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

        final RadioButton lightBtn = new RadioButton(context);
        lightBtn.setText("Light");
        lightBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

        final RadioButton darkBtn = new RadioButton(context);
        darkBtn.setText("Dark");
        darkBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

        final RadioButton[] themeBtns = new RadioButton[]{legacyBtn, autoBtn, lightBtn, darkBtn};
        View.OnClickListener themeClickListener = v -> {
            for (RadioButton rb : themeBtns) {
                rb.setChecked(rb == v);
            }
        };
        for (RadioButton rb : themeBtns) {
            rb.setOnClickListener(themeClickListener);
        }

        if ("auto".equals(currentTheme)) {
            autoBtn.setChecked(true);
        } else if ("light".equals(currentTheme)) {
            lightBtn.setChecked(true);
        } else if ("dark".equals(currentTheme)) {
            darkBtn.setChecked(true);
        } else {
            legacyBtn.setChecked(true);
        }

        LinearLayout themeRow1 = new LinearLayout(context);
        themeRow1.setOrientation(LinearLayout.HORIZONTAL);
        legacyBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        autoBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        themeRow1.addView(legacyBtn);
        themeRow1.addView(autoBtn);
        layout.addView(themeRow1);

        LinearLayout themeRow2 = new LinearLayout(context);
        themeRow2.setOrientation(LinearLayout.HORIZONTAL);
        lightBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        darkBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        themeRow2.addView(lightBtn);
        themeRow2.addView(darkBtn);
        layout.addView(themeRow2);

        // --- Keyboard Height Slider ---
        final TextView heightLabel = new TextView(context);
        heightLabel.setText("Keyboard height: " + currentHeight + "%");
        heightLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        heightLabel.setTypeface(Typeface.DEFAULT_BOLD);
        heightLabel.setPadding(0, (int) (6 * density), 0, (int) (2 * density));
        layout.addView(heightLabel);

        final SeekBar heightBar = new SeekBar(context);
        heightBar.setMax(60); // 70% to 130%
        heightBar.setProgress(currentHeight - 70);
        heightBar.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (2 * density));
        heightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                heightLabel.setText("Keyboard height: " + (70 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(heightBar);

        // --- Haptics ---
        final CheckBox hapticCheck = new CheckBox(context);
        hapticCheck.setText("Haptic feedback");
        hapticCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hapticCheck.setChecked(currentHaptic);
        hapticCheck.setPadding(0, (int) (4 * density), 0, 0);
        layout.addView(hapticCheck);

        final TextView vibrateLabel = new TextView(context);
        vibrateLabel.setText("Vibration: " + currentVibrateDuration + "ms");
        vibrateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        vibrateLabel.setTypeface(Typeface.DEFAULT_BOLD);
        vibrateLabel.setPadding(0, (int) (4 * density), 0, (int) (2 * density));
        layout.addView(vibrateLabel);

        final SeekBar vibrateBar = new SeekBar(context);
        vibrateBar.setMax(95); // 5ms to 100ms
        vibrateBar.setProgress(currentVibrateDuration - 5);
        vibrateBar.setEnabled(currentHaptic);
        vibrateBar.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (2 * density));
        vibrateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int duration = 5 + progress;
                vibrateLabel.setText("Vibration: " + duration + "ms");
                if (fromUser && hapticCheck.isChecked()) {
                    vibrate(duration);
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

        builder.setView(scrollView);
        builder.setPositiveButton("OK", (dialog, which) -> {
            boolean swipeCase = swipeCaseCheck.isChecked();
            boolean haptic = hapticCheck.isChecked();
            int vibrateDuration = 5 + vibrateBar.getProgress();
            boolean highContrast = contrastCheck.isChecked();
            int height = 70 + heightBar.getProgress();
            String selectedTheme = "legacy";
            if (autoBtn.isChecked()) {
                selectedTheme = "auto";
            } else if (lightBtn.isChecked()) {
                selectedTheme = "light";
            } else if (darkBtn.isChecked()) {
                selectedTheme = "dark";
            }

            prefs.edit()
                .putBoolean(PREF_SWIPE_CASE, swipeCase)
                .putBoolean(PREF_HAPTIC, haptic)
                .putInt(PREF_VIBRATE_DURATION, vibrateDuration)
                .putBoolean(PREF_HIGH_CONTRAST, highContrast)
                .putInt(PREF_HEIGHT_SCALE, height)
                .putString(PREF_THEME, selectedTheme)
                .apply();
            mSwipeCaseEnabled = swipeCase;
            mHapticEnabled = haptic;
            mVibrateDuration = vibrateDuration;
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
