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
import android.os.Handler;
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
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.lang.reflect.Field;
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
    private boolean mTopBarDirty = true;
    
    private LatinKeyboard mSymbolsKeyboard;
    private LatinKeyboard mSymbolsShiftedKeyboard;
    private LatinKeyboard mQwertyKeyboard;
    private LatinKeyboard mCurKeyboard;
    private Field mPreviewPopupField;
    private Field mHandlerField;
    private Field mPreviewTextField;
    private Handler mPopupHandler = new Handler();
    private Runnable mDismissPreviewRunnable = new Runnable() {
        @Override
        public void run() {
            dismissPreviewPopup();
        }
    };


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

    private LatinKeyboard getSymbolsKeyboard() {
        if (mSymbolsKeyboard == null) {
            final Context displayContext = getThemedContext();
            int displayWidth = getMaxWidth();
            int baseHeight = displayContext.getResources().getDisplayMetrics().heightPixels;
            float scale = getHeightScale();
            int displayHeight = (int) (baseHeight * scale);
            mSymbolsKeyboard = new LatinKeyboard(displayContext, R.xml.symbols, 0, displayWidth, displayHeight, scale);
            if (mQwertyKeyboard != null) {
                mSymbolsKeyboard.forceTotalHeight(mQwertyKeyboard.getHeight());
            }
        }
        return mSymbolsKeyboard;
    }

    private LatinKeyboard getSymbolsShiftedKeyboard() {
        if (mSymbolsShiftedKeyboard == null) {
            final Context displayContext = getThemedContext();
            int displayWidth = getMaxWidth();
            int baseHeight = displayContext.getResources().getDisplayMetrics().heightPixels;
            float scale = getHeightScale();
            int displayHeight = (int) (baseHeight * scale);
            mSymbolsShiftedKeyboard = new LatinKeyboard(displayContext, R.xml.symbols_shift, 0, displayWidth, displayHeight, scale);
            if (mQwertyKeyboard != null) {
                mSymbolsShiftedKeyboard.forceTotalHeight(mQwertyKeyboard.getHeight());
            }
        }
        return mSymbolsShiftedKeyboard;
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

        boolean wasSymbols = (mSymbolsKeyboard != null && mCurKeyboard == mSymbolsKeyboard);
        boolean wasSymbolsShifted = (mSymbolsShiftedKeyboard != null && mCurKeyboard == mSymbolsShiftedKeyboard);

        int qwertyRes = mNumberRowEnabled ? R.xml.qwerty_numbers : R.xml.qwerty;
        mQwertyKeyboard = new LatinKeyboard(displayContext, qwertyRes, 0, displayWidth, displayHeight, scale);
        mSymbolsKeyboard = null;
        mSymbolsShiftedKeyboard = null;

        if (wasSymbolsShifted) {
            mCurKeyboard = getSymbolsShiftedKeyboard();
        } else if (wasSymbols) {
            mCurKeyboard = getSymbolsKeyboard();
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
        try {
            mPreviewPopupField = KeyboardView.class.getDeclaredField("mPreviewPopup");
            mPreviewPopupField.setAccessible(true);
            PopupWindow pw = (PopupWindow) mPreviewPopupField.get(mInputView);
            if (pw != null) {
                pw.setAnimationStyle(0);
            }
            mHandlerField = KeyboardView.class.getDeclaredField("mHandler");
            mHandlerField.setAccessible(true);
        } catch (Throwable ignored) {}
        mInputView.setOnTouchListener(new View.OnTouchListener() {
            private float mDownX;
            private float mDownY;
            private float mDownRawX;
            private float mDownRawY;
            private boolean mSwipeHandled;
            private boolean mIsSpaceSliding;
            private float mLastSlideX;
            private float mAccumulatedSlideDelta;
            private boolean mStartedOnSpace;
            private boolean mIsActionTouch;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mLastPressedKey = 0;
                        mDownX = event.getX();
                        mDownY = event.getY();
                        mDownRawX = event.getRawX();
                        mDownRawY = event.getRawY();
                        mSwipeHandled = false;
                        mIsSpaceSliding = false;
                        mAccumulatedSlideDelta = 0f;
                        mStartedOnSpace = false;
                        mIsActionTouch = isActionKeyAt(mDownX, mDownY);
                        if (mIsActionTouch) {
                            mInputView.setPreviewEnabled(false);
                            dismissPreviewPopup();
                        } else {
                            mInputView.setPreviewEnabled(mKeyPreviewEnabled);
                        }
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
                    case MotionEvent.ACTION_POINTER_DOWN:
                        int downIndex = event.getActionIndex();
                        mDownX = event.getX(downIndex);
                        mDownY = event.getY(downIndex);
                        mDownRawX = event.getRawX();
                        mDownRawY = event.getRawY();
                        mIsActionTouch = isActionKeyAt(mDownX, mDownY);
                        if (mIsActionTouch) {
                            mInputView.setPreviewEnabled(false);
                            dismissPreviewPopup();
                        } else {
                            mInputView.setPreviewEnabled(mKeyPreviewEnabled);
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (mIsActionTouch) {
                            mInputView.setPreviewEnabled(false);
                        }
                        if (mSwipeHandled) {
                            return true;
                        }
                        float dx = event.getRawX() - mDownRawX;
                        float dy = event.getRawY() - mDownRawY;
                        float density = v.getResources().getDisplayMetrics().density;

                        if (mSpaceSlideEnabled && (mStartedOnSpace || mIsSpaceSliding)) {
                            if (mIsSpaceSliding) {
                                float deltaX = event.getRawX() - mLastSlideX;
                                mLastSlideX = event.getRawX();
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
                                    mLastSlideX = event.getRawX();
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
                        if (event.getPointerCount() == 1) {
                            event.setLocation(mDownX, mDownY);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (mIsActionTouch) {
                            mInputView.setPreviewEnabled(false);
                            dismissPreviewPopup();
                        } else {
                            mPopupHandler.removeCallbacks(mDismissPreviewRunnable);
                            mPopupHandler.postDelayed(mDismissPreviewRunnable, 60);
                        }
                        mIsActionTouch = false;
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
                        mStartedOnSpace = false;
                        if (event.getPointerCount() == 1) {
                            event.setLocation(mDownX, mDownY);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        mInputView.setPreviewEnabled(false);
                        dismissPreviewPopup();
                        mPopupHandler.removeCallbacks(mDismissPreviewRunnable);
                        mIsActionTouch = false;
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
                        mStartedOnSpace = false;
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
        mClipboardPanelView = null;
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
    public void onConfigureWindow(Window win, boolean isFullscreen, boolean isCandidatesOnly) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly);
        if (win != null) {
            win.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
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
        if (mQwertyKeyboard != null && nextKeyboard != mQwertyKeyboard) {
            nextKeyboard.forceTotalHeight(mQwertyKeyboard.getHeight());
        }
        mCurKeyboard = nextKeyboard;
        if (mInputView != null) {
            mInputView.setKeyboard(nextKeyboard);
        }
        updateCapsLockVisual();
    }

    private void updateCapsLockVisual() {
        if (mQwertyKeyboard != null) {
            mQwertyKeyboard.setCapsLock(mCapsLock);
        }
        if (mInputView != null) {
            mInputView.invalidateAllKeys();
        }
    }

    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        mCapsLock = false;
        updateCapsLockVisual();
        
        // We are now going to initialize our state based on the type of
        // text being edited.
        switch (attribute.inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_DATETIME:
            case InputType.TYPE_CLASS_PHONE:
                // Numbers and dates default to the symbols keyboard, with
                // no extra features.
                mCurKeyboard = getSymbolsKeyboard();
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

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        hideClipboardPanel();
        if (mTopBarChipsContainer != null) {
            mTopBarChipsContainer.removeAllViews();
            mTopBarDirty = true;
        }
        if (mClipboardPanelView != null && mContainerView != null) {
            mContainerView.removeView(mClipboardPanelView);
            mClipboardPanelView = null;
            mClipboardItemsLayout = null;
        }
        if (mCurKeyboard == mQwertyKeyboard) {
            mSymbolsKeyboard = null;
            mSymbolsShiftedKeyboard = null;
        }
        dismissPreviewPopup();
    }

    private void releaseInactiveResources() {
        dismissPreviewPopup();
        if (mTopBarChipsContainer != null) {
            mTopBarChipsContainer.removeAllViews();
            mTopBarDirty = true;
        }
        if (mClipboardPanelView != null && mContainerView != null) {
            mContainerView.removeView(mClipboardPanelView);
            mClipboardPanelView = null;
            mClipboardItemsLayout = null;
        }
        if (mCurKeyboard == mQwertyKeyboard) {
            mSymbolsKeyboard = null;
            mSymbolsShiftedKeyboard = null;
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            releaseInactiveResources();
            ClipboardHistory.trimMemory();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        releaseInactiveResources();
        ClipboardHistory.trimMemory();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (mClipboardManager != null && mClipListener != null) {
            try {
                mClipboardManager.removePrimaryClipChangedListener(mClipListener);
            } catch (Throwable ignored) {}
        }
        releaseInactiveResources();
        mQwertyKeyboard = null;
        mCurKeyboard = null;
        mInputView = null;
        mRootView = null;
        mContainerView = null;
        mTopBarLayout = null;
        mVibrator = null;
        ClipboardHistory.trimMemory();
    }
    
    @Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        hideClipboardPanel();
        mCapsLock = false;
        // Apply the selected keyboard to the input view.
        setLatinKeyboard(mCurKeyboard != null ? mCurKeyboard : mQwertyKeyboard);
        updateShiftKeyState(attribute);
        updateClipboardFromSystem();
        if (mTopBarDirty) {
            refreshTopBar();
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        updateClipboardFromSystem();
        if (mTopBarDirty) {
            refreshTopBar();
        }
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
                updateCapsLockVisual();
                return;
            }
            if (!mAutoCap) {
                mInputView.setShifted(false);
                updateCapsLockVisual();
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
            updateCapsLockVisual();
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
        if (isActionKey(primaryCode)) {
            if (mInputView != null) {
                mInputView.setPreviewEnabled(false);
            }
            dismissPreviewPopup();
        }
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
            if (current != null && (current == mSymbolsKeyboard || current == mSymbolsShiftedKeyboard)) {
                setLatinKeyboard(mQwertyKeyboard);
            } else {
                LatinKeyboard sym = getSymbolsKeyboard();
                sym.setShifted(false);
                setLatinKeyboard(sym);
            }
        } else {
            handleCharacter(primaryCode);
            if (primaryCode == 32) {
                Keyboard cur = mInputView != null ? mInputView.getKeyboard() : mCurKeyboard;
                if (cur != null && (cur == mSymbolsKeyboard || cur == mSymbolsShiftedKeyboard)) {
                    setLatinKeyboard(mQwertyKeyboard);
                }
            }
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
            updateCapsLockVisual();
        } else if (currentKeyboard != null && currentKeyboard == mSymbolsKeyboard) {
            if (mSymbolsKeyboard != null) {
                mSymbolsKeyboard.setShifted(true);
            }
            LatinKeyboard shifted = getSymbolsShiftedKeyboard();
            setLatinKeyboard(shifted);
            shifted.setShifted(true);
        } else if (currentKeyboard != null && currentKeyboard == mSymbolsShiftedKeyboard) {
            if (mSymbolsShiftedKeyboard != null) {
                mSymbolsShiftedKeyboard.setShifted(false);
            }
            LatinKeyboard sym = getSymbolsKeyboard();
            setLatinKeyboard(sym);
            sym.setShifted(false);
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
    
    private boolean isActionKey(int code) {
        return code < 0 || code == 32;
    }

    private boolean isActionKeyAt(float x, float y) {
        Keyboard keyboard = mCurKeyboard;
        if (keyboard == null && mInputView != null) {
            keyboard = mInputView.getKeyboard();
        }
        if (keyboard == null) return false;
        List<Keyboard.Key> keys = keyboard.getKeys();
        if (keys == null || keys.isEmpty()) return false;
        int ix = (int) x;
        int iy = (int) y;
        for (Keyboard.Key key : keys) {
            if (key.isInside(ix, iy)) {
                return key.codes != null && key.codes.length > 0 && isActionKey(key.codes[0]);
            }
        }
        for (Keyboard.Key key : keys) {
            if (ix >= key.x && ix <= (key.x + key.width)
                    && iy >= key.y && iy <= (key.y + key.height)) {
                return key.codes != null && key.codes.length > 0 && isActionKey(key.codes[0]);
            }
        }
        int minDist = Integer.MAX_VALUE;
        Keyboard.Key closestKey = null;
        for (Keyboard.Key key : keys) {
            int dist = key.squaredDistanceFrom(ix, iy);
            if (dist < minDist) {
                minDist = dist;
                closestKey = key;
            }
        }
        if (closestKey != null && closestKey.codes != null && closestKey.codes.length > 0) {
            return isActionKey(closestKey.codes[0]);
        }
        return false;
    }

    private void dismissPreviewPopup() {
        if (mInputView == null) return;
        try {
            if (mHandlerField == null) {
                mHandlerField = KeyboardView.class.getDeclaredField("mHandler");
                mHandlerField.setAccessible(true);
            }
            Handler handler = (Handler) mHandlerField.get(mInputView);
            if (handler != null) {
                handler.removeMessages(1); // MSG_SHOW_PREVIEW
                handler.removeMessages(2); // MSG_REMOVE_PREVIEW
            }
            if (mPreviewPopupField == null) {
                mPreviewPopupField = KeyboardView.class.getDeclaredField("mPreviewPopup");
                mPreviewPopupField.setAccessible(true);
            }
            PopupWindow pw = (PopupWindow) mPreviewPopupField.get(mInputView);
            if (pw != null) {
                pw.setAnimationStyle(0);
                if (pw.isShowing()) {
                    pw.dismiss();
                }
            }
            if (mPreviewTextField == null) {
                mPreviewTextField = KeyboardView.class.getDeclaredField("mPreviewText");
                mPreviewTextField.setAccessible(true);
            }
            View pt = (View) mPreviewTextField.get(mInputView);
            if (pt != null) {
                pt.setVisibility(View.INVISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    public void onPress(int primaryCode) {
        mLastPressedKey = primaryCode;
        if (mInputView != null) {
            if (isActionKey(primaryCode)) {
                mInputView.setPreviewEnabled(false);
                dismissPreviewPopup();
            } else {
                mInputView.setPreviewEnabled(mKeyPreviewEnabled);
            }
        }
        vibrate(mVibrateDuration);
    }
    
    public void onRelease(int primaryCode) {
        if (mLastPressedKey == primaryCode) {
            mLastPressedKey = 0;
        }
        if (isActionKey(primaryCode)) {
            if (mInputView != null) {
                mInputView.setPreviewEnabled(false);
            }
            dismissPreviewPopup();
        } else {
            mPopupHandler.removeCallbacks(mDismissPreviewRunnable);
            mPopupHandler.postDelayed(mDismissPreviewRunnable, 60);
        }
    }

    private interface ToggleListener {
        void onToggle(boolean isChecked);
    }

    private interface SliderListener {
        void onProgress(int progress, boolean fromUser);
    }

    private View createSectionHeader(Context context, String title, int accentColor, float density) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(accentColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tv.setLetterSpacing(0.06f);
        }
        tv.setPadding((int) (6 * density), (int) (10 * density), (int) (6 * density), (int) (4 * density));
        return tv;
    }

    private View createSectionDivider(Context context, int dividerColor, float density) {
        View v = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (1 * density)));
        lp.setMargins((int) (6 * density), (int) (8 * density), (int) (6 * density), (int) (2 * density));
        v.setLayoutParams(lp);
        v.setBackgroundColor(dividerColor);
        return v;
    }

    private View createCompactToggleRow(Context context, String title, String subtitle, boolean initialChecked,
            int onSurfaceColor, int onSurfaceVariantColor, int accentColor, boolean dark, float density,
            final ToggleListener listener, final boolean[] stateHolder) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (6 * density), (int) (6 * density), (int) (6 * density), (int) (6 * density));
        row.setClickable(true);
        row.setBackground(createPillDrawable(0, dark ? 0x1AFFFFFF : 0x0F000000, 0, 8 * density));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textCol.setLayoutParams(textLp);

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        titleTv.setTextColor(onSurfaceColor);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(titleTv);

        if (subtitle != null && subtitle.length() > 0) {
            TextView subTv = new TextView(context);
            subTv.setText(subtitle);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            subTv.setTextColor(onSurfaceVariantColor);
            subTv.setPadding(0, (int) (1 * density), 0, 0);
            textCol.addView(subTv);
        }
        row.addView(textCol);

        final Switch sw = new Switch(context);
        sw.setChecked(initialChecked);
        stateHolder[0] = initialChecked;
        sw.setClickable(false);
        sw.setFocusable(false);
        sw.setTextOn("");
        sw.setTextOff("");
        sw.setText(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            sw.setShowText(false);
            ColorStateList thumbTint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accentColor, dark ? 0xFF9E9E9E : 0xFFBDBDBD}
            );
            sw.setThumbTintList(thumbTint);
            ColorStateList trackTint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{dark ? 0x6682B1FF : 0x550061A4, dark ? 0x33FFFFFF : 0x24000000}
            );
            sw.setTrackTintList(trackTint);
        }
        row.addView(sw);

        row.setOnClickListener(v -> {
            boolean next = !sw.isChecked();
            sw.setChecked(next);
            stateHolder[0] = next;
            if (listener != null) {
                listener.onToggle(next);
            }
        });

        return row;
    }

    private View createCompactSlider(Context context, String label, String valueSuffix,
            int min, int max, int currentVal, int onSurfaceColor, int onSurfaceVariantColor,
            int accentColor, float density, final int[] valueHolder, final SliderListener listener) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding((int) (6 * density), (int) (4 * density), (int) (6 * density), (int) (4 * density));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelTv = new TextView(context);
        labelTv.setText(label);
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        labelTv.setTextColor(onSurfaceVariantColor);
        row.addView(labelTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        final TextView valTv = new TextView(context);
        valTv.setText(currentVal + valueSuffix);
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        valTv.setTypeface(Typeface.DEFAULT_BOLD);
        valTv.setTextColor(accentColor);
        row.addView(valTv);

        block.addView(row);

        SeekBar bar = new SeekBar(context);
        bar.setMax(max - min);
        bar.setProgress(currentVal - min);
        valueHolder[0] = currentVal;
        bar.setPadding((int) (4 * density), (int) (2 * density), (int) (4 * density), (int) (2 * density));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ColorStateList tint = ColorStateList.valueOf(accentColor);
            bar.setProgressTintList(tint);
            bar.setThumbTintList(tint);
        }

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = min + progress;
                valueHolder[0] = actual;
                valTv.setText(actual + valueSuffix);
                if (listener != null) {
                    listener.onProgress(actual, fromUser);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        block.addView(bar);
        return block;
    }

    private void updateThemeTabs(TextView[] tabs, String[] keys, String activeKey, int accentColor, int onSurfaceColor, int onSurfaceVariantColor, boolean dark, float density) {
        for (int i = 0; i < tabs.length; i++) {
            boolean isSel = keys[i].equals(activeKey);
            if (isSel) {
                GradientDrawable selBg = new GradientDrawable();
                selBg.setCornerRadius(15 * density);
                selBg.setColor(accentColor);
                tabs[i].setBackground(selBg);
                tabs[i].setTextColor(dark ? 0xFF001F3F : 0xFFFFFFFF);
                tabs[i].setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                tabs[i].setBackground(null);
                tabs[i].setTextColor(onSurfaceVariantColor);
                tabs[i].setTypeface(Typeface.DEFAULT);
            }
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
        ThemeColors tc = getThemeColors();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        int padH = (int) (18 * density);

        // Header: Settings + v1.0 badge
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(padH, (int) (16 * density), padH, (int) (6 * density));

        TextView titleView = new TextView(context);
        titleView.setText("Settings");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(tc.onSurfaceColor);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView versionBadge = new TextView(context);
        versionBadge.setText("v1.1.dev");
        versionBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        versionBadge.setTypeface(Typeface.DEFAULT_BOLD);
        versionBadge.setTextColor(tc.accentColor);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(tc.isDark ? 0x3382B1FF : 0x220061A4);
        badgeBg.setCornerRadius(10 * density);
        versionBadge.setBackground(badgeBg);
        int badgePadH = (int) (8 * density);
        int badgePadV = (int) (2.5f * density);
        versionBadge.setPadding(badgePadH, badgePadV, badgePadH, badgePadV);
        header.addView(versionBadge);

        builder.setCustomTitle(header);

        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        final int maxDialogHeight = (int) (screenHeight * 0.70f);

        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int heightMode = MeasureSpec.getMode(heightMeasureSpec);
                int heightSize = MeasureSpec.getSize(heightMeasureSpec);
                if (maxDialogHeight > 0) {
                    if (heightMode == MeasureSpec.UNSPECIFIED || heightSize > maxDialogHeight) {
                        heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxDialogHeight, MeasureSpec.AT_MOST);
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padH, (int) (2 * density), padH, (int) (8 * density));
        scrollView.addView(layout);

        // SECTION 1: TYPING & LAYOUT
        layout.addView(createSectionHeader(context, "TYPING & LAYOUT", tc.accentColor, density));

        final boolean[] numberRowHolder = new boolean[]{currentNumberRow};
        layout.addView(createCompactToggleRow(context, "Number row", "Top row for digits (1–0)", currentNumberRow,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, tc.isDark, density, null, numberRowHolder));

        final boolean[] keyPreviewHolder = new boolean[]{currentKeyPreview};
        layout.addView(createCompactToggleRow(context, "Key popup preview", "Magnify letter on press", currentKeyPreview,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, tc.isDark, density, null, keyPreviewHolder));

        final boolean[] spaceSlideHolder = new boolean[]{currentSpaceSlide};
        final int[] slideSensHolder = new int[]{currentSlideSensitivity};
        final View slideSensSlider = createCompactSlider(context, "Slide sensitivity", "%", 0, 100, currentSlideSensitivity,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, density, slideSensHolder, null);
        slideSensSlider.setVisibility(currentSpaceSlide ? View.VISIBLE : View.GONE);
        layout.addView(createCompactToggleRow(context, "Spacebar cursor slide", "Slide spacebar to move cursor", currentSpaceSlide,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, tc.isDark, density,
                isChecked -> slideSensSlider.setVisibility(isChecked ? View.VISIBLE : View.GONE),
                spaceSlideHolder));
        layout.addView(slideSensSlider);

        final boolean[] clipboardBarHolder = new boolean[]{currentClipboardBar};
        layout.addView(createCompactToggleRow(context, "Clipboard toolbar", "Quick-paste badges & drawer", currentClipboardBar,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, tc.isDark, density, null, clipboardBarHolder));

        final boolean[] autoCapHolder = new boolean[]{currentAutoCap};
        layout.addView(createCompactToggleRow(context, "Auto-capitalization", "Capitalize first word of sentences", currentAutoCap,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, tc.isDark, density, null, autoCapHolder));

        final boolean[] swipeCaseHolder = new boolean[]{currentSwipeCase};
        layout.addView(createCompactToggleRow(context, "Swipe letter for case", "▲ Up for upper, ▼ Down for lower", currentSwipeCase,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, tc.isDark, density, null, swipeCaseHolder));

        // DIVIDER
        layout.addView(createSectionDivider(context, tc.dividerColor, density));

        // SECTION 2: APPEARANCE
        layout.addView(createSectionHeader(context, "APPEARANCE", tc.accentColor, density));

        TextView themeLabel = new TextView(context);
        themeLabel.setText("Theme");
        themeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        themeLabel.setTextColor(tc.onSurfaceVariantColor);
        themeLabel.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (4 * density));
        layout.addView(themeLabel);

        // Theme Segmented Control
        LinearLayout themeContainer = new LinearLayout(context);
        themeContainer.setOrientation(LinearLayout.HORIZONTAL);
        themeContainer.setPadding((int) (3 * density), (int) (3 * density), (int) (3 * density), (int) (3 * density));
        GradientDrawable containerBg = new GradientDrawable();
        containerBg.setCornerRadius(18 * density);
        containerBg.setColor(tc.isDark ? 0xFF2A2830 : 0xFFE5E7EB);
        themeContainer.setBackground(containerBg);

        final String[] themes = new String[]{"legacy", "auto", "light", "dark"};
        final String[] themeLabels = new String[]{"Legacy", "Auto", "Light", "Dark"};
        final TextView[] themeTabs = new TextView[4];
        final String[] selectedTheme = new String[]{currentTheme};

        for (int i = 0; i < themes.length; i++) {
            final int index = i;
            TextView tab = new TextView(context);
            tab.setText(themeLabels[i]);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            tab.setLayoutParams(lp);
            tab.setClickable(true);
            themeTabs[i] = tab;
            tab.setOnClickListener(v -> {
                selectedTheme[0] = themes[index];
                updateThemeTabs(themeTabs, themes, selectedTheme[0], tc.accentColor, tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.isDark, density);
            });
            themeContainer.addView(tab);
        }
        updateThemeTabs(themeTabs, themes, selectedTheme[0], tc.accentColor, tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.isDark, density);
        layout.addView(themeContainer);

        final int[] heightHolder = new int[]{currentHeight};
        layout.addView(createCompactSlider(context, "Keyboard height", "%", 70, 130, currentHeight,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, density, heightHolder, null));

        final boolean[] highContrastHolder = new boolean[]{currentHighContrast};
        layout.addView(createCompactToggleRow(context, "High contrast & depth", "Elevated key borders & shadows", currentHighContrast,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, tc.isDark, density, null, highContrastHolder));

        // DIVIDER
        layout.addView(createSectionDivider(context, tc.dividerColor, density));

        // SECTION 3: HAPTIC FEEDBACK
        layout.addView(createSectionHeader(context, "HAPTIC FEEDBACK", tc.accentColor, density));

        final boolean[] hapticHolder = new boolean[]{currentHaptic};
        final int[] vibrateHolder = new int[]{currentVibrateDuration};
        final View vibrateSlider = createCompactSlider(context, "Vibration duration", " ms", 5, 100, currentVibrateDuration,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, density, vibrateHolder,
                (duration, fromUser) -> {
                    if (fromUser && hapticHolder[0]) {
                        vibrate(duration);
                    }
                });
        vibrateSlider.setVisibility(currentHaptic ? View.VISIBLE : View.GONE);
        layout.addView(createCompactToggleRow(context, "Haptic feedback", "Tactile feedback on keypress", currentHaptic,
                tc.onSurfaceColor, tc.onSurfaceVariantColor, tc.accentColor, tc.isDark, density,
                isChecked -> vibrateSlider.setVisibility(isChecked ? View.VISIBLE : View.GONE),
                hapticHolder));
        layout.addView(vibrateSlider);

        builder.setView(scrollView);
        builder.setPositiveButton("Save", (d, which) -> {
            boolean haptic = hapticHolder[0];
            int vibrateDuration = vibrateHolder[0];
            boolean highContrast = highContrastHolder[0];
            boolean swipeCase = swipeCaseHolder[0];
            boolean autoCap = autoCapHolder[0];
            boolean spaceSlide = spaceSlideHolder[0];
            int slideSensitivity = slideSensHolder[0];
            boolean clipboardBar = clipboardBarHolder[0];
            boolean keyPreview = keyPreviewHolder[0];
            boolean numberRow = numberRowHolder[0];
            int height = heightHolder[0];
            String selTheme = selectedTheme[0];

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
                .putString(PREF_THEME, selTheme)
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
            dialogBg.setCornerRadius(24 * density);
            dialogBg.setColor(tc.surfaceColor);
            window.setBackgroundDrawable(dialogBg);

            WindowManager.LayoutParams lp = window.getAttributes();
            lp.token = token;
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            if (lp.height > maxDialogHeight || lp.height == WindowManager.LayoutParams.MATCH_PARENT) {
                lp.height = maxDialogHeight;
            }
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
        dialog.show();

        if (dialog instanceof AlertDialog) {
            AlertDialog ad = (AlertDialog) dialog;
            if (ad.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                ad.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(tc.accentColor);
                ad.getButton(AlertDialog.BUTTON_POSITIVE).setTypeface(Typeface.DEFAULT_BOLD);
            }
            if (ad.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
                ad.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(tc.onSurfaceVariantColor);
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
        if (!isInputViewShown()) {
            mTopBarDirty = true;
            return;
        }
        mTopBarDirty = false;
        if (mTopBarChipsContainer == null) return;
        Context context = getThemedContext();
        float density = context.getResources().getDisplayMetrics().density;
        ThemeColors tc = getThemeColors();

        mTopBarChipsContainer.removeAllViews();

        List<String> clips = ClipboardHistory.getTopBarClips(this);
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
            ClipboardHistory.markClipUsed(SoftKeyboard.this, latest);
            refreshTopBar();
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
                ClipboardHistory.markClipUsed(SoftKeyboard.this, clip);
                refreshTopBar();
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
                ClipboardHistory.markClipUsed(SoftKeyboard.this, clipText);
                refreshTopBar();
                vibrate(mVibrateDuration);
                hideClipboardPanel();
            });

            mClipboardItemsLayout.addView(card);
        }
    }

    private void toggleClipboardPanel() {
        if (mInputView == null) return;
        if (mClipboardPanelView != null && mClipboardPanelView.getVisibility() == View.VISIBLE) {
            hideClipboardPanel();
        } else {
            showClipboardPanel();
        }
    }

    private void showClipboardPanel() {
        if (mInputView == null || mContainerView == null) return;
        if (mClipboardPanelView == null) {
            mClipboardPanelView = buildClipboardPanelView(getThemedContext());
            mContainerView.addView(mClipboardPanelView);
        }
        int h = 0;
        if (mInputView.getHeight() > 0) {
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
        if (mClipboardPanelView == null) return;
        mClipboardPanelView.setVisibility(View.GONE);
        if (mClipboardItemsLayout != null) {
            mClipboardItemsLayout.removeAllViews();
        }
        if (mInputView != null) {
            mInputView.setVisibility(View.VISIBLE);
        }
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
        if (!isInputViewShown()) {
            mTopBarDirty = true;
            return;
        }
        refreshTopBar();
        if (mClipboardPanelView != null && mClipboardPanelView.getVisibility() == View.VISIBLE) {
            updateClipboardPanel();
        }
    }
}
