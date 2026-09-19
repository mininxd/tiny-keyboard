package rkr.tinykeyboard.inputmethod;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public class ClipboardHistory {

    private static final String PREFS_NAME = "tiny_keyboard_prefs";
    private static final String PREF_CLIPS = "clipboard_history_items";
    private static final int MAX_CLIPS = 25;
    private static final int MAX_CHAR_LENGTH = 5000;

    public static synchronized List<String> getClips(Context context) {
        List<String> list = new ArrayList<String>();
        if (context == null) return list;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String jsonStr = prefs.getString(PREF_CLIPS, "[]");
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                String item = arr.optString(i);
                if (item != null && item.length() > 0) {
                    list.add(item);
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public static synchronized void addClip(Context context, String text) {
        if (context == null || text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_CHAR_LENGTH) {
            text = text.substring(0, MAX_CHAR_LENGTH);
        }

        try {
            List<String> current = getClips(context);
            // Deduplicate
            current.remove(text);
            current.add(0, text);

            while (current.size() > MAX_CLIPS) {
                current.remove(current.size() - 1);
            }

            saveClips(context, current);
        } catch (Throwable ignored) {}
    }

    public static synchronized void removeClip(Context context, String text) {
        if (context == null || text == null) return;
        try {
            List<String> current = getClips(context);
            if (current.remove(text)) {
                saveClips(context, current);
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized void clear(Context context) {
        if (context == null) return;
        try {
            saveClips(context, new ArrayList<String>());
        } catch (Throwable ignored) {}
    }

    public static synchronized String getLatestClip(Context context) {
        List<String> clips = getClips(context);
        return clips.isEmpty() ? null : clips.get(0);
    }

    private static void saveClips(Context context, List<String> list) {
        try {
            JSONArray arr = new JSONArray();
            for (String s : list) {
                arr.put(s);
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_CLIPS, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }
}
