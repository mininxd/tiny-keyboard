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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;

public class SoftKeyboard extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {

    private static final String PREFS_NAME = "tiny_keyboard_prefs";
    private static final String PREF_SWIPE_CASE = "swipe_case";
    private static final String PREF_HAPTIC = "haptic_feedback";
    private static final String PREF_VIBRATE_DURATION = "vibrate_duration";
    private static final String PREF_HIGH_CONTRAST = "high_contrast";
    private static final String PREF_HEIGHT_SCALE = "height_scale";
    private static final String PREF_THEME = "keyboard_theme";
    private static final String PREF_CLIPBOARD_BAR = "clipboard_bar";
    private static final String PREF_AUTO_CAP = "auto_cap";
    private static final String PREF_SPACE_SLIDE = "space_slide";
    private static final String PREF_SLIDE_SENSITIVITY = "slide_sensitivity";
    private static final String PREF_KEY_PREVIEW = "key_preview";
    private static final String PREF_NUMBER_ROW = "number_row";

    private InputMethodManager mInputMethodManager;
    private KeyboardView mInputView;
    private android.graphics.Insets mInsets;
    private Vibrator mVibrator;

    private LinearLayout mRootView;
    private FrameLayout mContainerView;
    private View mTopBarLayout;
    private LinearLayout mTopBarChipsContainer;
    private View mClipboardPanelView;
    private LinearLayout mClipboardItemsLayout;
    private ClipboardManager mClipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener mClipListener;
    private String mLastClipboardText = null;

    private int mLastDisplayWidth;
    private int mLastDisplayHeight;
    private boolean mCapsLock;
    private long mLastShiftTime;
    private boolean mSwipeCaseEnabled = false;
    private boolean mHapticEnabled = true;
    private int mVibrateDuration = 20; // 5ms to 100ms
    private boolean mHighContrast = true;
    private boolean mClipboardBarEnabled = true;
    private boolean mAutoCap = false; // default lowercase!
    private boolean mSpaceSlideEnabled = true;
    private int mSlideSensitivity = 50; // 0 to 100
    private boolean mKeyPreviewEnabled = true;
    private boolean mNumberRowEnabled = true;
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
        mClipboardBarEnabled = prefs.getBoolean(PREF_CLIPBOARD_BAR, true);
        mAutoCap = prefs.getBoolean(PREF_AUTO_CAP, false);
        mSpaceSlideEnabled = prefs.getBoolean(PREF_SPACE_SLIDE, true);
        mSlideSensitivity = prefs.getInt(PREF_SLIDE_SENSITIVITY, 50);
        mKeyPreviewEnabled = prefs.getBoolean(PREF_KEY_PREVIEW, true);
        mNumberRowEnabled = prefs.getBoolean(PREF_NUMBER_ROW, true);

        try {
            mClipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (mClipboardManager != null) {
                mClipListener = new ClipboardManager.OnPrimaryClipChangedListener() {
                    @Override
                    public void onPrimaryClipChanged() {
                        updateClipboardFromSystem();
                    }
                };
                mClipboardManager.addPrimaryClipChangedListener(mClipListener);
            }
        } catch (Throwable ignored) {}
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
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mNumberRowEnabled = prefs.getBoolean(PREF_NUMBER_ROW, true);

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

        int qwertyRes = mNumberRowEnabled ? R.xml.qwerty_numbers : R.xml.qwerty;
        mQwertyKeyboard = new LatinKeyboard(displayContext, qwertyRes, 0, displayWidth, displayHeight, scale);
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
        mClipboardBarEnabled = prefs.getBoolean(PREF_CLIPBOARD_BAR, true);
        mAutoCap = prefs.getBoolean(PREF_AUTO_CAP, false);
        mSpaceSlideEnabled = prefs.getBoolean(PREF_SPACE_SLIDE, true);
        mSlideSensitivity = prefs.getInt(PREF_SLIDE_SENSITIVITY, 50);
        mKeyPreviewEnabled = prefs.getBoolean(PREF_KEY_PREVIEW, true);
        mNumberRowEnabled = prefs.getBoolean(PREF_NUMBER_ROW, true);

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
        mInputView.setPreviewEnabled(mKeyPreviewEnabled);
        mInputView.setOnTouchListener(new View.OnTouchListener() {
            private float mDownX;
            private float mDownY;
            private boolean mSwipeHandled;
            private boolean mIsSpaceSliding;
            private float mLastSlideX;
            private float mAccumulatedSlideDelta;
            private boolean mStartedOnSpace;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownX = event.getX();
                        mDownY = event.getY();
                        mSwipeHandled = false;
                        mIsSpaceSliding = false;
                        mAccumulatedSlideDelta = 0f;
                        mStartedOnSpace = false;
                        if (mCurKeyboard != null) {
                            Keyboard.Key spaceKey = mCurKeyboard.getSpaceKey();
                            if (spaceKey != null) {
                                int x = (int) mDownX;
                                int y = (int) mDownY;
                                mStartedOnSpace = (x >= spaceKey.x && x <= (spaceKey.x + spaceKey.width)
                                        && y >= spaceKey.y && y <= (spaceKey.y + spaceKey.height));
                            }
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (mSwipeHandled) {
                            return true;
                        }
                        float dx = event.getX() - mDownX;
                        float dy = event.getY() - mDownY;
                        float density = v.getResources().getDisplayMetrics().density;

                        if (mSpaceSlideEnabled && (mStartedOnSpace || mLastPressedKey == 32 || mIsSpaceSliding)) {
                            if (mIsSpaceSliding) {
                                float deltaX = event.getX() - mLastSlideX;
                                mLastSlideX = event.getX();
                                mAccumulatedSlideDelta += deltaX;
                                float stepDp = 36f - (mSlideSensitivity * 0.24f);
                                float stepPx = Math.max(8 * density, stepDp * density);
                                while (mAccumulatedSlideDelta >= stepPx) {
                                    moveCursor(1);
                                    mAccumulatedSlideDelta -= stepPx;
                                }
                                while (mAccumulatedSlideDelta <= -stepPx) {
                                    moveCursor(-1);
                                    mAccumulatedSlideDelta += stepPx;
                                }
                                return true;
                            } else {
                                float startThreshold = 10 * density;
                                if (Math.abs(dx) > startThreshold && Math.abs(dx) > Math.abs(dy) * 1.1f) {
                                    mIsSpaceSliding = true;
                                    mLastPressedKey = 0;
                                    mLastSlideX = event.getX();
                                    mAccumulatedSlideDelta = 0f;
                                    cancelTouchOnView(v, event);
                                    moveCursor(dx > 0 ? 1 : -1);
                                    return true;
                                }
                            }
                        }

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
                        if (mIsSpaceSliding) {
                            mIsSpaceSliding = false;
                            mStartedOnSpace = false;
                            mLastPressedKey = 0;
                            return true;
                        }
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

        mRootView = new LinearLayout(context);
        mRootView.setOrientation(LinearLayout.VERTICAL);
        mRootView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mTopBarLayout = buildTopBarView(context);
        mTopBarLayout.setVisibility(mClipboardBarEnabled ? View.VISIBLE : View.GONE);
        mRootView.addView(mTopBarLayout);

        mContainerView = new FrameLayout(context);
        mContainerView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mContainerView.addView(mInputView);

        mClipboardPanelView = buildClipboardPanelView(context);
        mClipboardPanelView.setVisibility(View.GONE);
        mContainerView.addView(mClipboardPanelView);

        mRootView.addView(mContainerView);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setLayoutParams(mRootView);
            mRootView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                mInsets = windowInsets.getInsets(WindowInsets.Type.systemBars());
                setLayoutParams(mRootView);
                return WindowInsets.CONSUMED;
            });
        }

        refreshTopBar();
        return mRootView;
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
        mCapsLock = false;
        
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
        hideClipboardPanel();
        mCurKeyboard = mQwertyKeyboard;
        if (mInputView != null) {
            mInputView.closing();
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (mClipboardManager != null && mClipListener != null) {
            try {
                mClipboardManager.removePrimaryClipChangedListener(mClipListener);
            } catch (Throwable ignored) {}
        }
    }
    
    @Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        hideClipboardPanel();
        mCapsLock = false;
        // Apply the selected keyboard to the input view.
        setLatinKeyboard(mCurKeyboard);
        updateShiftKeyState(attribute);
        updateClipboardFromSystem();
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        updateClipboardFromSystem();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && mClipboardPanelView != null && mClipboardPanelView.getVisibility() == View.VISIBLE) {
            hideClipboardPanel();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
            if (mCapsLock) {
                mInputView.setShifted(true);
                return;
            }
            if (!mAutoCap) {
                mInputView.setShifted(false);
                return;
            }
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
            mInputView.setShifted(caps != 0);
        }
    }

    private void keyDownUp(int keyEventCode) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyEventCode));
        }
    }

    private void moveCursor(int direction) {
        keyDownUp(direction < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
        if (mHapticEnabled) {
            vibrate(Math.min(mVibrateDuration, 12));
        }
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
        IBinder token = null;
        if (mRootView != null && mRootView.getWindowToken() != null) {
            token = mRootView.getWindowToken();
        } else if (mInputView != null && mInputView.getWindowToken() != null) {
            token = mInputView.getWindowToken();
        }
        if (token == null) {
            return;
        }

        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean currentHaptic = prefs.getBoolean(PREF_HAPTIC, true);
        int currentVibrateDuration = prefs.getInt(PREF_VIBRATE_DURATION, 20);
        boolean currentHighContrast = prefs.getBoolean(PREF_HIGH_CONTRAST, true);
        boolean currentSwipeCase = prefs.getBoolean(PREF_SWIPE_CASE, false);
        boolean currentAutoCap = prefs.getBoolean(PREF_AUTO_CAP, false);
        boolean currentSpaceSlide = prefs.getBoolean(PREF_SPACE_SLIDE, true);
        int currentSlideSensitivity = prefs.getInt(PREF_SLIDE_SENSITIVITY, 50);
        boolean currentClipboardBar = prefs.getBoolean(PREF_CLIPBOARD_BAR, true);
        boolean currentKeyPreview = prefs.getBoolean(PREF_KEY_PREVIEW, true);
        boolean currentNumberRow = prefs.getBoolean(PREF_NUMBER_ROW, true);
        int currentHeight = prefs.getInt(PREF_HEIGHT_SCALE, 100);
        final String currentTheme = prefs.getString(PREF_THEME, "legacy");

        Context context = getDisplayContext();
        float density = context.getResources().getDisplayMetrics().density;

        boolean isNight = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "dark".equals(currentTheme) || ("auto".equals(currentTheme) && isNight) || "legacy".equals(currentTheme);

        int surfaceColor = dark ? 0xFF211F26 : 0xFFF3F4F9;
        int onSurfaceColor = dark ? 0xFFE6E1E5 : 0xFF1B1B1F;
        int onSurfaceVariantColor = dark ? 0xFFC4C7D0 : 0xFF44474E;
        int accentColor = dark ? 0xFF82B1FF : 0xFF0061A4;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        int padH = (int) (18 * density);
        int padV = (int) (6 * density);

        TextView customTitle = new TextView(context);
        customTitle.setText("Tiny Keyboard Settings");
        customTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        customTitle.setTypeface(Typeface.DEFAULT_BOLD);
        customTitle.setTextColor(onSurfaceColor);
        customTitle.setPadding(padH, (int) (16 * density), padH, (int) (4 * density));
        builder.setCustomTitle(customTitle);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padH, padV, padH, padV);
        scrollView.addView(layout);

        final CheckBox hapticCheck = new CheckBox(context);
        hapticCheck.setText("Haptic feedback");
        hapticCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hapticCheck.setTextColor(onSurfaceColor);
        hapticCheck.setMinimumHeight(0);
        hapticCheck.setPadding(hapticCheck.getPaddingLeft(), (int) (3 * density), hapticCheck.getPaddingRight(), (int) (3 * density));
        hapticCheck.setChecked(currentHaptic);
        layout.addView(hapticCheck);

        final TextView vibrateLabel = new TextView(context);
        vibrateLabel.setText("Vibration duration: " + currentVibrateDuration + "ms");
        vibrateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        vibrateLabel.setTextColor(onSurfaceVariantColor);
        vibrateLabel.setPadding((int) (2 * density), (int) (3 * density), 0, (int) (1 * density));
        layout.addView(vibrateLabel);

        final SeekBar vibrateBar = new SeekBar(context);
        vibrateBar.setMax(95); // 5ms to 100ms
        vibrateBar.setProgress(currentVibrateDuration - 5);
        vibrateBar.setEnabled(currentHaptic);
        vibrateBar.setPadding((int) (4 * density), (int) (2 * density), (int) (4 * density), (int) (2 * density));
        vibrateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int duration = 5 + progress;
                vibrateLabel.setText("Vibration duration: " + duration + "ms");
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

        final CheckBox contrastCheck = new CheckBox(context);
        contrastCheck.setText("High contrast");
        contrastCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        contrastCheck.setTextColor(onSurfaceColor);
        contrastCheck.setMinimumHeight(0);
        contrastCheck.setPadding(contrastCheck.getPaddingLeft(), (int) (3 * density), contrastCheck.getPaddingRight(), (int) (3 * density));
        contrastCheck.setChecked(currentHighContrast);
        layout.addView(contrastCheck);

        final CheckBox swipeCaseCheck = new CheckBox(context);
        swipeCaseCheck.setText("Swipe letter for case (▲ Upper, ▼ Lower)");
        swipeCaseCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        swipeCaseCheck.setTextColor(onSurfaceColor);
        swipeCaseCheck.setMinimumHeight(0);
        swipeCaseCheck.setPadding(swipeCaseCheck.getPaddingLeft(), (int) (3 * density), swipeCaseCheck.getPaddingRight(), (int) (3 * density));
        swipeCaseCheck.setChecked(currentSwipeCase);
        layout.addView(swipeCaseCheck);

        final CheckBox autoCapCheck = new CheckBox(context);
        autoCapCheck.setText("Auto-capitalization");
        autoCapCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        autoCapCheck.setTextColor(onSurfaceColor);
        autoCapCheck.setMinimumHeight(0);
        autoCapCheck.setPadding(autoCapCheck.getPaddingLeft(), (int) (3 * density), autoCapCheck.getPaddingRight(), (int) (3 * density));
        autoCapCheck.setChecked(currentAutoCap);
        layout.addView(autoCapCheck);

        final CheckBox spaceSlideCheck = new CheckBox(context);
        spaceSlideCheck.setText("Spacebar cursor slide");
        spaceSlideCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        spaceSlideCheck.setTextColor(onSurfaceColor);
        spaceSlideCheck.setMinimumHeight(0);
        spaceSlideCheck.setPadding(spaceSlideCheck.getPaddingLeft(), (int) (3 * density), spaceSlideCheck.getPaddingRight(), (int) (3 * density));
        spaceSlideCheck.setChecked(currentSpaceSlide);
        layout.addView(spaceSlideCheck);

        final TextView slideSensLabel = new TextView(context);
        slideSensLabel.setText("Slide sensitivity: " + currentSlideSensitivity + "%");
        slideSensLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        slideSensLabel.setTextColor(onSurfaceVariantColor);
        slideSensLabel.setPadding((int) (2 * density), (int) (3 * density), 0, (int) (1 * density));
        layout.addView(slideSensLabel);

        final SeekBar slideSensBar = new SeekBar(context);
        slideSensBar.setMax(100);
        slideSensBar.setProgress(currentSlideSensitivity);
        slideSensBar.setEnabled(currentSpaceSlide);
        slideSensBar.setPadding((int) (4 * density), (int) (2 * density), (int) (4 * density), (int) (2 * density));
        slideSensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                slideSensLabel.setText("Slide sensitivity: " + progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(slideSensBar);

        spaceSlideCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            slideSensBar.setEnabled(isChecked);
            slideSensLabel.setAlpha(isChecked ? 1.0f : 0.5f);
        });
        if (!currentSpaceSlide) {
            slideSensLabel.setAlpha(0.5f);
        }

        final CheckBox clipboardBarCheck = new CheckBox(context);
        clipboardBarCheck.setText("Clipboard toolbar");
        clipboardBarCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clipboardBarCheck.setTextColor(onSurfaceColor);
        clipboardBarCheck.setMinimumHeight(0);
        clipboardBarCheck.setPadding(clipboardBarCheck.getPaddingLeft(), (int) (3 * density), clipboardBarCheck.getPaddingRight(), (int) (3 * density));
        clipboardBarCheck.setChecked(currentClipboardBar);
        layout.addView(clipboardBarCheck);

        final CheckBox keyPreviewCheck = new CheckBox(context);
        keyPreviewCheck.setText("Key popup preview");
        keyPreviewCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        keyPreviewCheck.setTextColor(onSurfaceColor);
        keyPreviewCheck.setMinimumHeight(0);
        keyPreviewCheck.setPadding(keyPreviewCheck.getPaddingLeft(), (int) (3 * density), keyPreviewCheck.getPaddingRight(), (int) (3 * density));
        keyPreviewCheck.setChecked(currentKeyPreview);
        layout.addView(keyPreviewCheck);

        final CheckBox numberRowCheck = new CheckBox(context);
        numberRowCheck.setText("Number row on top");
        numberRowCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        numberRowCheck.setTextColor(onSurfaceColor);
        numberRowCheck.setMinimumHeight(0);
        numberRowCheck.setPadding(numberRowCheck.getPaddingLeft(), (int) (3 * density), numberRowCheck.getPaddingRight(), (int) (3 * density));
        numberRowCheck.setChecked(currentNumberRow);
        layout.addView(numberRowCheck);

        final TextView themeLabel = new TextView(context);
        themeLabel.setText("Theme");
        themeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        themeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        themeLabel.setTextColor(onSurfaceVariantColor);
        themeLabel.setPadding((int) (2 * density), (int) (6 * density), 0, (int) (1 * density));
        layout.addView(themeLabel);

        final RadioGroup themeGroup = new RadioGroup(context);
        themeGroup.setOrientation(RadioGroup.VERTICAL);

        final RadioButton legacyBtn = new RadioButton(context);
        legacyBtn.setId(1);
        legacyBtn.setText("Legacy");
        legacyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        legacyBtn.setTextColor(onSurfaceColor);
        legacyBtn.setMinimumHeight(0);
        legacyBtn.setPadding(legacyBtn.getPaddingLeft(), (int) (2.5f * density), legacyBtn.getPaddingRight(), (int) (2.5f * density));
        themeGroup.addView(legacyBtn);

        final RadioButton autoBtn = new RadioButton(context);
        autoBtn.setId(2);
        autoBtn.setText("Auto");
        autoBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        autoBtn.setTextColor(onSurfaceColor);
        autoBtn.setMinimumHeight(0);
        autoBtn.setPadding(autoBtn.getPaddingLeft(), (int) (2.5f * density), autoBtn.getPaddingRight(), (int) (2.5f * density));
        themeGroup.addView(autoBtn);

        final RadioButton lightBtn = new RadioButton(context);
        lightBtn.setId(3);
        lightBtn.setText("Light");
        lightBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        lightBtn.setTextColor(onSurfaceColor);
        lightBtn.setMinimumHeight(0);
        lightBtn.setPadding(lightBtn.getPaddingLeft(), (int) (2.5f * density), lightBtn.getPaddingRight(), (int) (2.5f * density));
        themeGroup.addView(lightBtn);

        final RadioButton darkBtn = new RadioButton(context);
        darkBtn.setId(4);
        darkBtn.setText("Dark");
        darkBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        darkBtn.setTextColor(onSurfaceColor);
        darkBtn.setMinimumHeight(0);
        darkBtn.setPadding(darkBtn.getPaddingLeft(), (int) (2.5f * density), darkBtn.getPaddingRight(), (int) (2.5f * density));
        themeGroup.addView(darkBtn);

        if ("auto".equals(currentTheme)) {
            autoBtn.setChecked(true);
        } else if ("light".equals(currentTheme)) {
            lightBtn.setChecked(true);
        } else if ("dark".equals(currentTheme)) {
            darkBtn.setChecked(true);
        } else {
            legacyBtn.setChecked(true);
        }
        layout.addView(themeGroup);

        final TextView heightLabel = new TextView(context);
        heightLabel.setText("Keyboard height: " + currentHeight + "%");
        heightLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        heightLabel.setTypeface(Typeface.DEFAULT_BOLD);
        heightLabel.setTextColor(onSurfaceVariantColor);
        heightLabel.setPadding((int) (2 * density), (int) (6 * density), 0, (int) (1 * density));
        layout.addView(heightLabel);

        final SeekBar heightBar = new SeekBar(context);
        heightBar.setMax(60); // 70% to 130%
        heightBar.setProgress(currentHeight - 70);
        heightBar.setPadding((int) (4 * density), (int) (2 * density), (int) (4 * density), (int) (2 * density));
        heightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                heightLabel.setText("Keyboard height: " + (70 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(heightBar);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ColorStateList tint = ColorStateList.valueOf(accentColor);
            hapticCheck.setButtonTintList(tint);
            contrastCheck.setButtonTintList(tint);
            swipeCaseCheck.setButtonTintList(tint);
            autoCapCheck.setButtonTintList(tint);
            spaceSlideCheck.setButtonTintList(tint);
            slideSensBar.setProgressTintList(tint);
            slideSensBar.setThumbTintList(tint);
            clipboardBarCheck.setButtonTintList(tint);
            keyPreviewCheck.setButtonTintList(tint);
            numberRowCheck.setButtonTintList(tint);
            legacyBtn.setButtonTintList(tint);
            autoBtn.setButtonTintList(tint);
            lightBtn.setButtonTintList(tint);
            darkBtn.setButtonTintList(tint);
            vibrateBar.setProgressTintList(tint);
            vibrateBar.setThumbTintList(tint);
            heightBar.setProgressTintList(tint);
            heightBar.setThumbTintList(tint);
        }

        builder.setView(scrollView);
        builder.setPositiveButton("OK", (dialog, which) -> {
            boolean haptic = hapticCheck.isChecked();
            int vibrateDuration = 5 + vibrateBar.getProgress();
            boolean highContrast = contrastCheck.isChecked();
            boolean swipeCase = swipeCaseCheck.isChecked();
            boolean autoCap = autoCapCheck.isChecked();
            boolean spaceSlide = spaceSlideCheck.isChecked();
            int slideSensitivity = slideSensBar.getProgress();
            boolean clipboardBar = clipboardBarCheck.isChecked();
            boolean keyPreview = keyPreviewCheck.isChecked();
            boolean numberRow = numberRowCheck.isChecked();
            int height = 70 + heightBar.getProgress();
            int checkedThemeId = themeGroup.getCheckedRadioButtonId();
            String selectedTheme = "legacy";
            if (checkedThemeId == 1) {
                selectedTheme = "legacy";
            } else if (checkedThemeId == 2) {
                selectedTheme = "auto";
            } else if (checkedThemeId == 3) {
                selectedTheme = "light";
            } else if (checkedThemeId == 4) {
                selectedTheme = "dark";
            }

            prefs.edit()
                .putBoolean(PREF_HAPTIC, haptic)
                .putInt(PREF_VIBRATE_DURATION, vibrateDuration)
                .putBoolean(PREF_HIGH_CONTRAST, highContrast)
                .putBoolean(PREF_SWIPE_CASE, swipeCase)
                .putBoolean(PREF_AUTO_CAP, autoCap)
                .putBoolean(PREF_SPACE_SLIDE, spaceSlide)
                .putInt(PREF_SLIDE_SENSITIVITY, slideSensitivity)
                .putBoolean(PREF_CLIPBOARD_BAR, clipboardBar)
                .putBoolean(PREF_KEY_PREVIEW, keyPreview)
                .putBoolean(PREF_NUMBER_ROW, numberRow)
                .putInt(PREF_HEIGHT_SCALE, height)
                .putString(PREF_THEME, selectedTheme)
                .apply();
            mHapticEnabled = haptic;
            mVibrateDuration = vibrateDuration;
            mHighContrast = highContrast;
            mSwipeCaseEnabled = swipeCase;
            mAutoCap = autoCap;
            mSpaceSlideEnabled = spaceSlide;
            mSlideSensitivity = slideSensitivity;
            mClipboardBarEnabled = clipboardBar;
            mKeyPreviewEnabled = keyPreview;
            mNumberRowEnabled = numberRow;
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
            GradientDrawable dialogBg = new GradientDrawable();
            dialogBg.setCornerRadius(28 * density);
            dialogBg.setColor(surfaceColor);
            window.setBackgroundDrawable(dialogBg);

            WindowManager.LayoutParams lp = window.getAttributes();
            lp.token = token;
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
        dialog.show();

        if (dialog instanceof AlertDialog) {
            AlertDialog ad = (AlertDialog) dialog;
            if (ad.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                ad.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accentColor);
                ad.getButton(AlertDialog.BUTTON_POSITIVE).setTypeface(Typeface.DEFAULT_BOLD);
            }
            if (ad.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
                ad.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(onSurfaceVariantColor);
            }
        }
    }

    private static class ThemeColors {
        boolean isDark;
        int surfaceColor;
        int onSurfaceColor;
        int onSurfaceVariantColor;
        int accentColor;
        int chipBgColor;
        int chipPressedColor;
        int chipStrokeColor;
        int dividerColor;
    }

    private ThemeColors getThemeColors() {
        Context context = getThemedContext();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String theme = prefs.getString(PREF_THEME, "legacy");
        boolean highContrast = prefs.getBoolean(PREF_HIGH_CONTRAST, true);

        boolean isNight = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "dark".equals(theme) || ("auto".equals(theme) && isNight) || "legacy".equals(theme);

        ThemeColors tc = new ThemeColors();
        tc.isDark = dark;
        tc.surfaceColor = dark ? 0xFF211F26 : 0xFFF3F4F9;
        tc.onSurfaceColor = dark ? 0xFFE6E1E5 : 0xFF1B1B1F;
        tc.onSurfaceVariantColor = dark ? 0xFFC4C7D0 : 0xFF44474E;
        tc.accentColor = dark ? 0xFF82B1FF : 0xFF0061A4;
        tc.chipBgColor = dark ? 0xFF2D3036 : 0xFFFFFFFF;
        tc.chipPressedColor = dark ? 0xFF40434C : 0xFFD6D9E2;
        tc.chipStrokeColor = highContrast ? (dark ? 0xFF555864 : 0xFFB6BBC8) : 0;
        tc.dividerColor = dark ? 0x22FFFFFF : 0x1A000000;
        return tc;
    }

    private Drawable createPillDrawable(int normalColor, int pressedColor, int strokeColor, float radius) {
        StateListDrawable sld = new StateListDrawable();

        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(radius);
        pressed.setColor(pressedColor);
        if (strokeColor != 0) {
            pressed.setStroke(1, strokeColor);
        }

        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(radius);
        normal.setColor(normalColor);
        if (strokeColor != 0) {
            normal.setStroke(1, strokeColor);
        }

        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    private View buildTopBarView(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        ThemeColors tc = getThemeColors();

        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int barHeight = (int) (36 * density);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, barHeight));
        topBar.setBackgroundColor(tc.surfaceColor);
        topBar.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (2 * density));

        // Clipboard toggle button
        TextView cbBtn = new TextView(context);
        cbBtn.setText("📋");
        cbBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cbBtn.setGravity(Gravity.CENTER);
        cbBtn.setBackground(createPillDrawable(0, tc.chipPressedColor, 0, 14 * density));
        int padH = (int) (8 * density);
        int padV = (int) (4 * density);
        cbBtn.setPadding(padH, padV, padH, padV);
        cbBtn.setOnClickListener(v -> toggleClipboardPanel());
        topBar.addView(cbBtn);

        // Horizontal scroll container for recent copied text chips
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
        scrollParams.setMargins((int) (4 * density), 0, 0, 0);
        scroll.setLayoutParams(scrollParams);

        mTopBarChipsContainer = new LinearLayout(context);
        mTopBarChipsContainer.setOrientation(LinearLayout.HORIZONTAL);
        mTopBarChipsContainer.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(mTopBarChipsContainer, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        topBar.addView(scroll);

        return topBar;
    }

    private void refreshTopBar() {
        if (mTopBarChipsContainer == null) return;
        Context context = getThemedContext();
        float density = context.getResources().getDisplayMetrics().density;
        ThemeColors tc = getThemeColors();

        mTopBarChipsContainer.removeAllViews();

        List<String> clips = ClipboardHistory.getClips(this);
        if (clips.isEmpty()) return;

        // 1. Prominent Quick-Paste Pill (for the latest clip)
        final String latest = clips.get(0);
        String preview = latest.replace('\n', ' ').trim();
        if (preview.length() > 22) {
            preview = preview.substring(0, 22) + "…";
        }

        TextView pill = new TextView(context);
        pill.setText("📋 " + preview);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setTextColor(tc.accentColor);
        pill.setSingleLine(true);
        pill.setGravity(Gravity.CENTER);

        int pillBg = tc.isDark ? 0x3382B1FF : 0x220061A4;
        int pillPressed = tc.isDark ? 0x5582B1FF : 0x440061A4;
        int pillStroke = tc.accentColor;
        pill.setBackground(createPillDrawable(pillBg, pillPressed, pillStroke, 14 * density));

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, (int) (26 * density));
        pillLp.setMargins(0, 0, (int) (6 * density), 0);
        pill.setLayoutParams(pillLp);
        pill.setPadding((int) (10 * density), 0, (int) (10 * density), 0);

        pill.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitText(latest, 1);
                updateShiftKeyState(getCurrentInputEditorInfo());
            }
            vibrate(mVibrateDuration);
        });
        mTopBarChipsContainer.addView(pill);

        // Additional recent clips as secondary pills
        for (int i = 1; i < Math.min(clips.size(), 5); i++) {
            final String clip = clips.get(i);
            String subPrev = clip.replace('\n', ' ').trim();
            if (subPrev.length() > 16) {
                subPrev = subPrev.substring(0, 16) + "…";
            }

            TextView subPill = new TextView(context);
            subPill.setText(subPrev);
            subPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            subPill.setTextColor(tc.onSurfaceColor);
            subPill.setSingleLine(true);
            subPill.setGravity(Gravity.CENTER);
            subPill.setBackground(createPillDrawable(tc.chipBgColor, tc.chipPressedColor, tc.chipStrokeColor, 13 * density));

            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (24 * density));
            subLp.setMargins(0, 0, (int) (5 * density), 0);
            subPill.setLayoutParams(subLp);
            subPill.setPadding((int) (8 * density), 0, (int) (8 * density), 0);

            subPill.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.commitText(clip, 1);
                    updateShiftKeyState(getCurrentInputEditorInfo());
                }
                vibrate(mVibrateDuration);
            });
            mTopBarChipsContainer.addView(subPill);
        }
    }

    private View buildClipboardPanelView(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        ThemeColors tc = getThemeColors();

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(tc.surfaceColor);

        int kbHeight = mCurKeyboard != null ? mCurKeyboard.getHeight() : (int) (240 * density);
        panel.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, kbHeight));

        // Header layout
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int) (40 * density)));
        header.setPadding((int) (4 * density), 0, (int) (8 * density), 0);

        // Back button
        TextView backBtn = new TextView(context);
        backBtn.setText("←");
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        backBtn.setTextColor(tc.onSurfaceColor);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setBackground(createPillDrawable(0, tc.chipPressedColor, 0, 16 * density));
        int padH = (int) (12 * density);
        int padV = (int) (6 * density);
        backBtn.setPadding(padH, padV, padH, padV);
        backBtn.setOnClickListener(v -> hideClipboardPanel());
        header.addView(backBtn);

        // Title
        TextView title = new TextView(context);
        title.setText("Clipboard");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(tc.onSurfaceColor);
        title.setPadding((int) (6 * density), 0, 0, 0);
        header.addView(title);

        // Spacer
        View spacer = new View(context);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1.0f));

        // Clear All button
        TextView clearBtn = new TextView(context);
        clearBtn.setText("Clear all");
        clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clearBtn.setTypeface(Typeface.DEFAULT_BOLD);
        clearBtn.setTextColor(tc.accentColor);
        clearBtn.setBackground(createPillDrawable(0, tc.chipPressedColor, 0, 14 * density));
        clearBtn.setPadding(padH, padV, padH, padV);
        clearBtn.setOnClickListener(v -> {
            ClipboardHistory.clear(SoftKeyboard.this);
            updateClipboardPanel();
            refreshTopBar();
            vibrate(mVibrateDuration);
        });
        header.addView(clearBtn);

        panel.addView(header);

        // Divider below header
        View headerDivider = new View(context);
        headerDivider.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * density)));
        headerDivider.setBackgroundColor(tc.dividerColor);
        panel.addView(headerDivider);

        // ScrollView for items
        ScrollView scrollView = new ScrollView(context);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        mClipboardItemsLayout = new LinearLayout(context);
        mClipboardItemsLayout.setOrientation(LinearLayout.VERTICAL);
        mClipboardItemsLayout.setPadding((int) (10 * density), (int) (8 * density), (int) (10 * density), (int) (12 * density));
        scrollView.addView(mClipboardItemsLayout, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(scrollView);
        return panel;
    }

    private void updateClipboardPanel() {
        if (mClipboardItemsLayout == null) return;
        Context context = getThemedContext();
        float density = context.getResources().getDisplayMetrics().density;
        ThemeColors tc = getThemeColors();

        mClipboardItemsLayout.removeAllViews();
        List<String> clips = ClipboardHistory.getClips(this);

        if (clips.isEmpty()) {
            LinearLayout emptyLayout = new LinearLayout(context);
            emptyLayout.setOrientation(LinearLayout.VERTICAL);
            emptyLayout.setGravity(Gravity.CENTER);
            emptyLayout.setPadding(0, (int) (40 * density), 0, (int) (20 * density));

            TextView emptyIcon = new TextView(context);
            emptyIcon.setText("📋");
            emptyIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
            emptyIcon.setGravity(Gravity.CENTER);
            emptyLayout.addView(emptyIcon);

            TextView emptyTitle = new TextView(context);
            emptyTitle.setText("Clipboard is empty");
            emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
            emptyTitle.setTextColor(tc.onSurfaceColor);
            emptyTitle.setGravity(Gravity.CENTER);
            emptyTitle.setPadding(0, (int) (8 * density), 0, (int) (2 * density));
            emptyLayout.addView(emptyTitle);

            TextView emptySub = new TextView(context);
            emptySub.setText("Copied text will appear here automatically.");
            emptySub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            emptySub.setTextColor(tc.onSurfaceVariantColor);
            emptySub.setGravity(Gravity.CENTER);
            emptyLayout.addView(emptySub);

            mClipboardItemsLayout.addView(emptyLayout);
            return;
        }

        for (final String clipText : clips) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(createPillDrawable(tc.chipBgColor, tc.chipPressedColor, tc.chipStrokeColor, 12 * density));
            card.setPadding((int) (12 * density), (int) (10 * density), (int) (8 * density), (int) (10 * density));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, 0, (int) (6 * density));
            card.setLayoutParams(cardLp);

            // Text content
            TextView text = new TextView(context);
            text.setText(clipText);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            text.setTextColor(tc.onSurfaceColor);
            text.setMaxLines(3);
            text.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            // Delete button for this item
            TextView delBtn = new TextView(context);
            delBtn.setText("✕");
            delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            delBtn.setTextColor(tc.onSurfaceVariantColor);
            delBtn.setGravity(Gravity.CENTER);
            delBtn.setBackground(createPillDrawable(0, tc.chipPressedColor, 0, 12 * density));
            delBtn.setPadding((int) (10 * density), (int) (6 * density), (int) (10 * density), (int) (6 * density));
            delBtn.setOnClickListener(v -> {
                ClipboardHistory.removeClip(SoftKeyboard.this, clipText);
                updateClipboardPanel();
                refreshTopBar();
                vibrate(mVibrateDuration);
            });
            card.addView(delBtn);

            // Clicking the card pastes the text and switches back to the keyboard
            card.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.commitText(clipText, 1);
                    updateShiftKeyState(getCurrentInputEditorInfo());
                }
                vibrate(mVibrateDuration);
                hideClipboardPanel();
            });

            mClipboardItemsLayout.addView(card);
        }
    }

    private void toggleClipboardPanel() {
        if (mClipboardPanelView == null || mInputView == null) return;
        if (mClipboardPanelView.getVisibility() == View.VISIBLE) {
            hideClipboardPanel();
        } else {
            showClipboardPanel();
        }
    }

    private void showClipboardPanel() {
        if (mClipboardPanelView == null || mInputView == null) return;
        int h = 0;
        if (mInputView != null && mInputView.getHeight() > 0) {
            h = mInputView.getHeight();
        } else if (mCurKeyboard != null && mCurKeyboard.getHeight() > 0) {
            h = mCurKeyboard.getHeight();
        }
        if (h > 0) {
            ViewGroup.LayoutParams lp = mClipboardPanelView.getLayoutParams();
            if (lp != null) {
                lp.height = h;
                mClipboardPanelView.setLayoutParams(lp);
            }
        }
        updateClipboardPanel();
        mInputView.setVisibility(View.GONE);
        mClipboardPanelView.setVisibility(View.VISIBLE);
        vibrate(mVibrateDuration);
    }

    private void hideClipboardPanel() {
        if (mClipboardPanelView == null || mInputView == null) return;
        mClipboardPanelView.setVisibility(View.GONE);
        mInputView.setVisibility(View.VISIBLE);
    }

    private void updateClipboardFromSystem() {
        if (mClipboardManager == null) return;
        try {
            if (mClipboardManager.hasPrimaryClip()) {
                ClipData clip = mClipboardManager.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).coerceToText(this);
                    if (text != null && text.length() > 0) {
                        String str = text.toString();
                        if (!str.equals(mLastClipboardText)) {
                            mLastClipboardText = str;
                            ClipboardHistory.addClip(this, str);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        refreshTopBar();
        if (mClipboardPanelView != null && mClipboardPanelView.getVisibility() == View.VISIBLE) {
            updateClipboardPanel();
        }
    }
}
