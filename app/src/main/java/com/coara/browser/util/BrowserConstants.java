package com.coara.browser.util;

import java.util.regex.Pattern;

public final class BrowserConstants {
    private BrowserConstants() {}

    public static final String PREF_NAME = "AdvancedBrowserPrefs";
    public static final String KEY_CURRENT_TAB_ID = "current_tab_id";
    public static final String KEY_DARK_MODE = "dark_mode";
    public static final String KEY_BASIC_AUTH = "basic_auth";
    public static final String KEY_ZOOM_ENABLED = "zoom_enabled";
    public static final String KEY_JS_ENABLED = "js_enabled";
    public static final String KEY_IMG_BLOCK_ENABLED = "img_block_enabled";
    public static final String KEY_UA_ENABLED = "ua_enabled";
    public static final String KEY_DESKUA_ENABLED = "deskua_enabled";
    public static final String KEY_CT3UA_ENABLED = "ct3ua_enabled";
    public static final String KEY_TABS = "tabs";
    public static final String KEY_CURRENT_TAB = "current_tab_index";
    public static final String KEY_BOOKMARKS = "bookmarks";
    public static final String KEY_HISTORY = "history";
    public static final String KEY_DOWNLOAD_HISTORY = "download_history";

    public static final String APPEND_STR = " CoaraBrowser";
    public static final String START_PAGE = "file:///android_asset/index.html";
    public static final int FILE_SELECT_CODE = 1001;
    public static final int MAX_TABS = 30;
    public static final int MAX_HISTORY_SIZE = 100;
    public static final String SENTINEL_FILENAME = "cache_sentinel.txt";
    public static final String EXTRA_CLEAR_HISTORY = "com.coara.browser.EXTRA_CLEAR_HISTORY";

    public static final Pattern CACHE_MODE_PATTERN =
            Pattern.compile("(^|[/.])(?:(chatx2|chatx|chat|auth|nicovideo|login|disk|cgi|session|cloud))($|[/.])",
                    Pattern.CASE_INSENSITIVE);
}
