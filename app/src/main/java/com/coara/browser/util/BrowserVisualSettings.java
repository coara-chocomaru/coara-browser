package com.coara.browser.util;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

public final class BrowserVisualSettings {
    public static final String KEY_TEXT_COLOR = "text_color";
    public static final String KEY_FONT_FAMILY = "font_family";
    public static final String KEY_BACKGROUND_TRANSPARENT = "background_transparent";
    public static final String KEY_BACKGROUND_PATH = "background_image_path";

    private static final String DEFAULT = "default";
    private static final String STYLE_ID = "__coara_browser_visual_style";

    private static final String[] TEXT_COLOR_LABELS = {
            "赤", "青", "緑", "黄色", "ピンク", "グレー", "茶色", "デフォルト"
    };

    private static final String[] TEXT_COLOR_VALUES = {
            "#F44336", "#2196F3", "#4CAF50", "#FFC107", "#E91E63", "#757575", "#795548", DEFAULT
    };

    private static final String[] FONT_LABELS = {
            "デフォルト",
            "Sans Serif",
            "Sans Serif Light",
            "Sans Serif Medium",
            "Sans Serif Condensed",
            "Sans Serif Condensed Light",
            "Sans Serif Black",
            "Sans Serif Thin",
            "Sans Serif Extra Light",
            "Sans Serif Small Caps",
            "Serif",
            "Monospace",
            "Cursive",
            "Fantasy"
    };

    private static final String[] FONT_VALUES = {
            DEFAULT,
            "sans-serif",
            "sans-serif-light",
            "sans-serif-medium",
            "sans-serif-condensed",
            "sans-serif-condensed-light",
            "sans-serif-black",
            "sans-serif-thin",
            "sans-serif-extra-light",
            "sans-serif-smallcaps",
            "serif",
            "monospace",
            "cursive",
            "fantasy"
    };

    private BrowserVisualSettings() {
    }

    public static String[] getTextColorLabels() {
        return TEXT_COLOR_LABELS.clone();
    }

    public static String[] getTextColorValues() {
        return TEXT_COLOR_VALUES.clone();
    }

    public static String[] getFontLabels() {
        return FONT_LABELS.clone();
    }

    public static String[] getFontValues() {
        return FONT_VALUES.clone();
    }

    public static int getTextColorIndex(SharedPreferences preferences) {
        String value = getTextColor(preferences);
        for (int i = 0; i < TEXT_COLOR_VALUES.length; i++) {
            if (TEXT_COLOR_VALUES[i].equals(value)) {
                return i;
            }
        }
        return TEXT_COLOR_VALUES.length - 1;
    }

    public static int getFontIndex(SharedPreferences preferences) {
        String value = getFontFamily(preferences);
        for (int i = 0; i < FONT_VALUES.length; i++) {
            if (FONT_VALUES[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    public static String getTextColor(SharedPreferences preferences) {
        String value = preferences.getString(KEY_TEXT_COLOR, DEFAULT);
        if (value == null) {
            return DEFAULT;
        }
        for (String allowed : TEXT_COLOR_VALUES) {
            if (allowed.equals(value)) {
                return value;
            }
        }
        return DEFAULT;
    }

    public static void setTextColor(SharedPreferences preferences, String value) {
        if (DEFAULT.equals(value)) {
            preferences.edit().remove(KEY_TEXT_COLOR).commit();
            return;
        }
        for (String allowed : TEXT_COLOR_VALUES) {
            if (allowed.equals(value)) {
                preferences.edit().putString(KEY_TEXT_COLOR, value).commit();
                return;
            }
        }
        preferences.edit().remove(KEY_TEXT_COLOR).commit();
    }

    public static String getFontFamily(SharedPreferences preferences) {
        String value = preferences.getString(KEY_FONT_FAMILY, DEFAULT);
        if (value == null) {
            return DEFAULT;
        }
        for (String allowed : FONT_VALUES) {
            if (allowed.equals(value)) {
                return value;
            }
        }
        return DEFAULT;
    }

    public static void setFontFamily(SharedPreferences preferences, String value) {
        if (DEFAULT.equals(value)) {
            preferences.edit().remove(KEY_FONT_FAMILY).commit();
            return;
        }
        for (String allowed : FONT_VALUES) {
            if (allowed.equals(value)) {
                preferences.edit().putString(KEY_FONT_FAMILY, value).commit();
                return;
            }
        }
        preferences.edit().remove(KEY_FONT_FAMILY).commit();
    }

    public static boolean isTransparencyEnabled(SharedPreferences preferences) {
        return preferences.getBoolean(KEY_BACKGROUND_TRANSPARENT, false);
    }

    public static void setTransparencyEnabled(SharedPreferences preferences, boolean enabled) {
        if (enabled) {
            preferences.edit().putBoolean(KEY_BACKGROUND_TRANSPARENT, true).commit();
        } else {
            preferences.edit().remove(KEY_BACKGROUND_TRANSPARENT).commit();
        }
    }

    public static String getBackgroundPath(SharedPreferences preferences) {
        String path = preferences.getString(KEY_BACKGROUND_PATH, null);
        return path == null || path.trim().isEmpty() ? null : path;
    }

    public static void setBackgroundPath(SharedPreferences preferences, String path) {
        if (path == null || path.trim().isEmpty()) {
            preferences.edit().remove(KEY_BACKGROUND_PATH).commit();
        } else {
            preferences.edit().putString(KEY_BACKGROUND_PATH, path).commit();
        }
    }

    public static int resolveTextColor(SharedPreferences preferences) {
        String color = getTextColor(preferences);
        if (DEFAULT.equals(color)) {
            return Color.TRANSPARENT;
        }
        try {
            return Color.parseColor(color);
        } catch (Exception ignored) {
            return Color.TRANSPARENT;
        }
    }

    public static void applyToWebView(WebView webView, SharedPreferences preferences) {
        if (webView == null) {
            return;
        }

        String fontFamily = getFontFamily(preferences);
        boolean transparency = isTransparencyEnabled(preferences);
        WebSettings settings = webView.getSettings();

        try {
            if (DEFAULT.equals(fontFamily)) {
                settings.setStandardFontFamily("sans-serif");
                settings.setFixedFontFamily("monospace");
                settings.setSansSerifFontFamily("sans-serif");
                settings.setSerifFontFamily("serif");
                settings.setCursiveFontFamily("cursive");
                settings.setFantasyFontFamily("fantasy");
            } else {
                settings.setStandardFontFamily(fontFamily);
                settings.setFixedFontFamily(fontFamily);
                settings.setSansSerifFontFamily(fontFamily);
                settings.setSerifFontFamily(fontFamily);
                settings.setCursiveFontFamily(fontFamily);
                settings.setFantasyFontFamily(fontFamily);
            }
        } catch (Exception ignored) {
        }

        try {
            webView.setBackgroundColor(transparency ? Color.TRANSPARENT : Color.WHITE);
        } catch (Exception ignored) {
        }

        String textColor = getTextColor(preferences);
        boolean hasCss = hasCustomVisualCss(preferences);
        if (!hasCss) {
            return;
        }

        injectStyle(webView, textColor, fontFamily, transparency);
    }

    public static boolean hasCustomVisualCss(SharedPreferences preferences) {
        return isTransparencyEnabled(preferences)
                || !DEFAULT.equals(getTextColor(preferences))
                || !DEFAULT.equals(getFontFamily(preferences));
    }

    public static void clearInjectedStyle(WebView webView) {
        removeStyle(webView);
    }

    private static void injectStyle(WebView webView, String textColor, String fontFamily, boolean transparency) {
        try {
            StringBuilder css = new StringBuilder();
            if (!DEFAULT.equals(textColor)) {
                css.append("body,body *{color:")
                        .append(textColor)
                        .append(" !important;}");
            }
            if (!DEFAULT.equals(fontFamily)) {
                css.append("body,body *{font-family:")
                        .append(fontFamily)
                        .append(" !important;}");
            }
            if (transparency) {
                css.append("html,body,body:before,body:after{background:transparent !important;background-color:transparent !important;}");
            }

            String script = "(function(){"
                    + "var id=" + JSONObject.quote(STYLE_ID) + ";"
                    + "var old=document.getElementById(id);"
                    + "if(old&&old.parentNode){old.parentNode.removeChild(old);}"
                    + "var s=document.createElement('style');"
                    + "s.id=id;"
                    + "s.type='text/css';"
                    + "s.appendChild(document.createTextNode(" + JSONObject.quote(css.toString()) + "));"
                    + "(document.head||document.documentElement).appendChild(s);"
                    + "})();";
            webView.evaluateJavascript(script, null);
        } catch (Exception ignored) {
        }
    }

    private static void removeStyle(WebView webView) {
        try {
            String script = "(function(){"
                    + "var s=document.getElementById(" + JSONObject.quote(STYLE_ID) + ");"
                    + "if(s&&s.parentNode){s.parentNode.removeChild(s);}"
                    + "})();";
            webView.evaluateJavascript(script, null);
        } catch (Exception ignored) {
        }
    }
}
