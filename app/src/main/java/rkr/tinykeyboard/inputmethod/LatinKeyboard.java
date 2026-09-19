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

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.inputmethodservice.Keyboard;
import android.view.inputmethod.EditorInfo;

public class LatinKeyboard extends Keyboard {

    public static final int KEYCODE_LANGUAGE_SWITCH = -101;

    private Key mEnterKey;
    private Key mShiftKey;
    private Key mSpaceKey;
    private Key mLanguageSwitchKey;
    private Key mSavedSpaceKey;
    private Key mSavedLanguageSwitchKey;
    private int mTotalHeight;

    public LatinKeyboard(Context context, int xmlLayoutResId) {
        super(context, xmlLayoutResId);
        mTotalHeight = super.getHeight();
    }

    public LatinKeyboard(Context context, int xmlLayoutResId, int modeId, int width, int height) {
        this(context, xmlLayoutResId, modeId, width, height, 1.0f);
    }

    public LatinKeyboard(Context context, int xmlLayoutResId, int modeId, int width, int height, float scale) {
        super(context, xmlLayoutResId, modeId, width, height);
        applyScale(scale);
    }

    private void applyScale(float scale) {
        if (scale == 1.0f || scale <= 0) {
            mTotalHeight = super.getHeight();
            return;
        }
        int maxBottom = 0;
        for (Key key : getKeys()) {
            key.height = Math.round(key.height * scale);
            key.y = Math.round(key.y * scale);
            if (key.y + key.height > maxBottom) {
                maxBottom = key.y + key.height;
            }
        }
        if (mSavedSpaceKey != null) {
            mSavedSpaceKey.height = Math.round(mSavedSpaceKey.height * scale);
            mSavedSpaceKey.y = Math.round(mSavedSpaceKey.y * scale);
        }
        if (mSavedLanguageSwitchKey != null) {
            mSavedLanguageSwitchKey.height = Math.round(mSavedLanguageSwitchKey.height * scale);
            mSavedLanguageSwitchKey.y = Math.round(mSavedLanguageSwitchKey.y * scale);
        }
        mTotalHeight = maxBottom > 0 ? maxBottom : Math.round(super.getHeight() * scale);
    }

    @Override
    public int getHeight() {
        if (mTotalHeight > 0) {
            return mTotalHeight;
        }
        return super.getHeight();
    }

    public void forceTotalHeight(int targetHeight) {
        if (targetHeight <= 0) return;
        int currentHeight = getHeight();
        if (currentHeight <= 0 || currentHeight == targetHeight) return;

        float ratio = (float) targetHeight / (float) currentHeight;
        int maxBottom = 0;
        for (Key key : getKeys()) {
            key.y = Math.round(key.y * ratio);
            key.height = Math.round(key.height * ratio);
            maxBottom = Math.max(maxBottom, key.y + key.height);
        }
        if (mSavedSpaceKey != null) {
            mSavedSpaceKey.y = Math.round(mSavedSpaceKey.y * ratio);
            mSavedSpaceKey.height = Math.round(mSavedSpaceKey.height * ratio);
        }
        if (mSavedLanguageSwitchKey != null) {
            mSavedLanguageSwitchKey.y = Math.round(mSavedLanguageSwitchKey.y * ratio);
            mSavedLanguageSwitchKey.height = Math.round(mSavedLanguageSwitchKey.height * ratio);
        }
        int diff = targetHeight - maxBottom;
        if (diff != 0) {
            for (Key key : getKeys()) {
                if (key.y + key.height == maxBottom) {
                    key.height += diff;
                }
            }
        }
        mTotalHeight = targetHeight;
    }

    @Override
    protected Key createKeyFromXml(Resources res, Row parent, int x, int y, XmlResourceParser parser) {
        Key key = new Key(res, parent, x, y, parser);
        int code = key.codes[0];
        if (code < 0 || code == 32) {
            key.modifier = true;
        }
        if (code == Keyboard.KEYCODE_DONE) {
            mEnterKey = key;
        } else if (code == Keyboard.KEYCODE_SHIFT) {
            mShiftKey = key;
        } else if (code == 32) {
            mSpaceKey = key;
            mSavedSpaceKey = new Key(res, parent, x, y, parser);
            mSavedSpaceKey.modifier = true;
        } else if (code == KEYCODE_LANGUAGE_SWITCH) {
            mLanguageSwitchKey = key;
            mSavedLanguageSwitchKey = new Key(res, parent, x, y, parser);
            mSavedLanguageSwitchKey.modifier = true;
        }
        return key;
    }

    public void setCapsLock(boolean isCapsLock) {
        if (mShiftKey != null) {
            mShiftKey.label = isCapsLock ? "⇪" : "⇧";
            mShiftKey.on = isCapsLock;
        }
    }

    public Key getSpaceKey() {
        return mSpaceKey;
    }

    void setLanguageSwitchKeyVisibility(boolean visible) {
        if (mSpaceKey == null || mLanguageSwitchKey == null) {
            return;
        }
        if (visible) {
            mSpaceKey.width = mSavedSpaceKey.width;
            mSpaceKey.x = mSavedSpaceKey.x;
            mLanguageSwitchKey.width = mSavedLanguageSwitchKey.width;
            mLanguageSwitchKey.label = mSavedLanguageSwitchKey.label;
        } else {
            mSpaceKey.width = mSavedSpaceKey.width + mSavedLanguageSwitchKey.width;
            mSpaceKey.x = mSavedSpaceKey.x - mSavedLanguageSwitchKey.width;
            mLanguageSwitchKey.width = 0;
            mLanguageSwitchKey.label = null;
        }
    }

    void setImeOptions(Resources res, int options) {
        if (mEnterKey == null) {
            return;
        }

        switch (options&(EditorInfo.IME_MASK_ACTION|EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
            case EditorInfo.IME_ACTION_GO:
                mEnterKey.label = res.getText(R.string.label_go_key);
                break;
            case EditorInfo.IME_ACTION_NEXT:
                mEnterKey.label = res.getText(R.string.label_next_key);
                break;
            case EditorInfo.IME_ACTION_SEARCH:
                mEnterKey.label = res.getString(R.string.search);
                break;
            case EditorInfo.IME_ACTION_SEND:
                mEnterKey.label = res.getText(R.string.label_send_key);
                break;
            default:
                mEnterKey.label = res.getString(R.string.enter);
                break;
        }
    }
}
