package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Preferences and tiny helpers for the DeltaGram "Interface & appearance" section.
 *
 * Every getter has a safe default so the client behaves exactly like stock Telegram
 * until the user turns a switch on.
 */
public final class DeltaGramUi {

    public static final String KEY_TIME_FORMAT = "dg_time_format"; // -1 system, 0 = 12h, 1 = 24h
    public static final String KEY_FULL_DATE = "dg_full_date";
    public static final String KEY_COMPACT_DATES = "dg_compact_dates";
    public static final String KEY_STICKY_DATES = "dg_sticky_dates";
    public static final String KEY_COLORED_NAMES = "dg_colored_names";
    public static final String KEY_GRADIENT_NAMES = "dg_gradient_names";
    public static final String KEY_MONO_CODE = "dg_mono_code";
    public static final String KEY_SYNTAX_HIGHLIGHT = "dg_syntax_highlight";
    public static final String KEY_DIALOG_STRIPE = "dg_dialog_stripe";
    public static final String KEY_UNREAD_ONLY = "dg_unread_only";
    public static final String KEY_SEARCH_FILTERS = "dg_search_filters";
    public static final String KEY_QUICK_PROFILE = "dg_quick_profile";
    public static final String KEY_COPY_LINK_MENU = "dg_copy_link_menu";
    public static final String KEY_CHAT_OVERFLOW = "dg_chat_overflow";
    public static final String KEY_EDGE_SWIPE = "dg_edge_swipe"; // 0 any swipe, 1 edge only, 2 disabled
    public static final String KEY_FIXED_KEYBOARD_PANEL = "dg_fixed_keyboard_panel";
    public static final String KEY_MESSAGE_ANIM = "dg_message_anim";

    private DeltaGramUi() {
    }

    public static SharedPreferences prefs() {
        // Read "mainconfig" directly: this helper is also used while LocaleController
        // is being constructed, when MessagesController.getInstance(0) must not be touched.
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
    }

    /* ------------------------------------------------------------------ */
    /* Time & dates                                                        */
    /* ------------------------------------------------------------------ */

    public static int getTimeFormat() {
        return prefs().getInt(KEY_TIME_FORMAT, -1);
    }

    public static void setTimeFormat(int mode) {
        prefs().edit().putInt(KEY_TIME_FORMAT, mode).apply();
    }

    public static boolean resolve24HourFormat() {
        final int mode = getTimeFormat();
        if (mode == 0) {
            return false;
        }
        if (mode == 1) {
            return true;
        }
        try {
            return DateFormat.is24HourFormat(ApplicationLoader.applicationContext);
        } catch (Throwable e) {
            return false;
        }
    }

    public static void applyTimeFormat() {
        final boolean value = resolve24HourFormat();
        if (LocaleController.is24HourFormat != value) {
            LocaleController.is24HourFormat = value;
            try {
                final LocaleController controller = LocaleController.getInstance();
                if (controller != null) {
                    controller.recreateFormatters();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    public static boolean fullDate() {
        return prefs().getBoolean(KEY_FULL_DATE, false);
    }

    public static boolean compactDates() {
        return prefs().getBoolean(KEY_COMPACT_DATES, false);
    }

    public static boolean stickyDates() {
        return prefs().getBoolean(KEY_STICKY_DATES, false);
    }

    public static String formatNumericDate(long dateSeconds) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
            return format.format(new Date(dateSeconds * 1000L));
        } catch (Throwable e) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ */
    /* Names & code                                                        */
    /* ------------------------------------------------------------------ */

    public static boolean coloredNames() {
        return prefs().getBoolean(KEY_COLORED_NAMES, false);
    }

    public static boolean gradientNames() {
        return prefs().getBoolean(KEY_GRADIENT_NAMES, false);
    }

    public static boolean monoCode() {
        return prefs().getBoolean(KEY_MONO_CODE, true);
    }

    public static boolean syntaxHighlight() {
        return prefs().getBoolean(KEY_SYNTAX_HIGHLIGHT, true);
    }

    public static int nameColorFor(long id) {
        long value = id < 0 ? -id : id;
        float hue = (value * 137.508f) % 360f;
        return Color.HSVToColor(new float[]{hue, 0.55f, 0.92f});
    }

    public static int nameColorSecondaryFor(long id) {
        long value = id < 0 ? -id : id;
        float hue = (value * 137.508f + 48f) % 360f;
        return Color.HSVToColor(new float[]{hue, 0.62f, 0.86f});
    }

    /* ------------------------------------------------------------------ */
    /* Chat list                                                           */
    /* ------------------------------------------------------------------ */

    public static boolean dialogStripe() {
        return prefs().getBoolean(KEY_DIALOG_STRIPE, false);
    }

    public static boolean unreadOnly() {
        return prefs().getBoolean(KEY_UNREAD_ONLY, false);
    }

    public static boolean searchFilters() {
        return prefs().getBoolean(KEY_SEARCH_FILTERS, true);
    }

    public static boolean quickProfile() {
        return prefs().getBoolean(KEY_QUICK_PROFILE, false);
    }

    /* ------------------------------------------------------------------ */
    /* Menus, gestures, input                                              */
    /* ------------------------------------------------------------------ */

    public static boolean copyLinkMenu() {
        return prefs().getBoolean(KEY_COPY_LINK_MENU, true);
    }

    public static boolean chatOverflow() {
        return prefs().getBoolean(KEY_CHAT_OVERFLOW, false);
    }

    public static int getEdgeSwipeMode() {
        return prefs().getInt(KEY_EDGE_SWIPE, 0);
    }

    public static boolean fixedKeyboardPanel() {
        return prefs().getBoolean(KEY_FIXED_KEYBOARD_PANEL, false);
    }

    public static boolean messageAnim() {
        return prefs().getBoolean(KEY_MESSAGE_ANIM, false);
    }
}
