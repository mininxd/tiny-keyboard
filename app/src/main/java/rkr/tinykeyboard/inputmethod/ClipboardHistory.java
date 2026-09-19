package rkr.tinykeyboard.inputmethod;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public class ClipboardHistory {

    private static final String PREFS_NAME = "tiny_keyboard_prefs";
    private static final String PREF_CLIPS = "clipboard_history_items";
    private static final String PREF_USED_CLIPS = "used_clipboard_items";
    private static final int MAX_CLIPS = 10;
    private static final int MAX_CHAR_LENGTH = 1000;

    private static List<String> sCachedClips = null;
    private static List<String> sCachedUsed = null;

    private static List<String> readList(Context context, String key) {
        List<String> list = new ArrayList<String>();
        if (context == null) return list;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String item = arr.optString(i);
                if (item != null && item.length() > 0) {
                    list.add(item);
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private static void writeList(Context context, String key, List<String> list) {
        if (context == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (String s : list) {
                arr.put(s);
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(key, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    public static synchronized void trimMemory() {
        sCachedClips = null;
        sCachedUsed = null;
    }

    public static synchronized List<String> getClips(Context context) {
        if (sCachedClips == null) {
            sCachedClips = readList(context, PREF_CLIPS);
        }
        return new ArrayList<String>(sCachedClips);
    }

    public static synchronized void markClipUsed(Context context, String text) {
        if (context == null || text == null) return;
        if (sCachedUsed == null) {
            sCachedUsed = readList(context, PREF_USED_CLIPS);
        }
        if (!sCachedUsed.contains(text)) {
            sCachedUsed.add(text);
            writeList(context, PREF_USED_CLIPS, sCachedUsed);
        }
    }

    public static synchronized void unmarkClipUsed(Context context, String text) {
        if (context == null || text == null) return;
        if (sCachedUsed == null) {
            sCachedUsed = readList(context, PREF_USED_CLIPS);
        }
        if (sCachedUsed.remove(text)) {
            writeList(context, PREF_USED_CLIPS, sCachedUsed);
        }
    }

    public static synchronized List<String> getTopBarClips(Context context) {
        if (sCachedClips == null) {
            sCachedClips = readList(context, PREF_CLIPS);
        }
        if (sCachedUsed == null) {
            sCachedUsed = readList(context, PREF_USED_CLIPS);
        }
        List<String> result = new ArrayList<String>();
        for (String s : sCachedClips) {
            if (!sCachedUsed.contains(s)) {
                result.add(s);
            }
        }
        return result;
    }

    public static synchronized void addClip(Context context, String text) {
        if (context == null || text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_CHAR_LENGTH) {
            text = text.substring(0, MAX_CHAR_LENGTH);
        }

        try {
            if (sCachedClips == null) {
                sCachedClips = readList(context, PREF_CLIPS);
            }
            sCachedClips.remove(text);
            sCachedClips.add(0, text);
            while (sCachedClips.size() > MAX_CLIPS) {
                sCachedClips.remove(sCachedClips.size() - 1);
            }
            writeList(context, PREF_CLIPS, sCachedClips);
            unmarkClipUsed(context, text);
        } catch (Throwable ignored) {}
    }

    public static synchronized void removeClip(Context context, String text) {
        if (context == null || text == null) return;
        try {
            if (sCachedClips == null) {
                sCachedClips = readList(context, PREF_CLIPS);
            }
            if (sCachedClips.remove(text)) {
                writeList(context, PREF_CLIPS, sCachedClips);
            }
            unmarkClipUsed(context, text);
        } catch (Throwable ignored) {}
    }

    public static synchronized void clear(Context context) {
        if (context == null) return;
        try {
            sCachedClips = new ArrayList<String>();
            sCachedUsed = new ArrayList<String>();
            writeList(context, PREF_CLIPS, sCachedClips);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(PREF_USED_CLIPS).apply();
        } catch (Throwable ignored) {}
    }

    public static synchronized String getLatestClip(Context context) {
        if (sCachedClips == null) {
            sCachedClips = readList(context, PREF_CLIPS);
        }
        return sCachedClips.isEmpty() ? null : sCachedClips.get(0);
    }
}
