/*
 * Adapted from exteraGram's ZalgoFilter.java (Copyright @exteraGram, 2022-2026).
 * GNU GPL v3 or later; see https://github.com/exteraSquad/exteraGram
 * and EXTERAGRAM_ADDITIONAL_TERMS.md. Modified for DeltaGram's preferences.
 */
package org.telegram.messenger;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;

import java.util.ArrayList;

public final class DeltaZalgoFilter {
    private static final char REPLACEMENT = '\u2060';
    private static final int MAX_ZALGO_MARKS = 2;
    private static final int MAX_SCRIPT_MARKS = 4;

    private DeltaZalgoFilter() {
    }

    private static boolean enabled() {
        return MessagesController.getGlobalMainSettings().getBoolean("delta_filter_zalgo", true);
    }

    public static CharSequence filterSpannable(CharSequence text) {
        if (!enabled() || TextUtils.isEmpty(text)) return text;
        if (!(text instanceof Spannable)) return filterEnabled(text.toString());
        Spannable source = (Spannable) text;
        String result = filterEnabled(source.toString());
        if (result.equals(source.toString())) return text;
        Spannable output = new SpannableString(result);
        for (Object span : source.getSpans(0, source.length(), Object.class)) {
            int start = Math.max(0, Math.min(source.getSpanStart(span), result.length()));
            int end = Math.max(start, Math.min(source.getSpanEnd(span), result.length()));
            output.setSpan(span, start, end, source.getSpanFlags(span));
        }
        return output;
    }

    public static String filter(String text) {
        if (!enabled() || TextUtils.isEmpty(text)) return text;
        return filterEnabled(text);
    }

    private static String filterEnabled(String text) {
        ArrayList<int[]> ranges = findReplacementRanges(text);
        if (ranges == null) return text;
        StringBuilder result = new StringBuilder(text.length());
        int lastEnd = 0;
        for (int[] range : ranges) {
            result.append(text, lastEnd, range[0]);
            for (int i = range[0]; i < range[1]; i++) result.append(REPLACEMENT);
            lastEnd = range[1];
        }
        return result.append(text, lastEnd, text.length()).toString();
    }

    private static ArrayList<int[]> findReplacementRanges(CharSequence text) {
        ArrayList<int[]> ranges = null;
        int start = -1;
        int marks = 0;
        int limit = MAX_SCRIPT_MARKS;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            int chars = Character.charCount(cp);
            int allowed = allowedMarks(cp);
            if (allowed > 0) {
                if (start < 0) {
                    start = i;
                    marks = 0;
                    limit = allowed;
                } else {
                    limit = Math.min(limit, allowed);
                }
                marks++;
            } else {
                if (start >= 0 && marks > limit) {
                    if (ranges == null) ranges = new ArrayList<>();
                    ranges.add(new int[]{start, i});
                }
                start = -1;
                marks = 0;
                limit = MAX_SCRIPT_MARKS;
                if (isDirectionControl(cp)) {
                    if (ranges == null) ranges = new ArrayList<>();
                    ranges.add(new int[]{i, i + chars});
                }
            }
            i += chars;
        }
        if (start >= 0 && marks > limit) {
            if (ranges == null) ranges = new ArrayList<>();
            ranges.add(new int[]{start, text.length()});
        }
        return ranges;
    }

    private static int allowedMarks(int cp) {
        if (cp < 0x0300) return 0;
        int type = Character.getType(cp);
        if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK) return 0;
        return inZalgoRange(cp) ? MAX_ZALGO_MARKS : MAX_SCRIPT_MARKS;
    }

    private static boolean inZalgoRange(int cp) {
        return (cp >= 0x0300 && cp <= 0x036F)
                || (cp >= 0x1AB0 && cp <= 0x1AFF)
                || (cp >= 0x1DC0 && cp <= 0x1DFF)
                || (cp >= 0x20D0 && cp <= 0x20FF)
                || (cp >= 0xFE20 && cp <= 0xFE2F);
    }

    private static boolean isDirectionControl(int cp) {
        return cp == 0x061C || cp == 0x200E || cp == 0x200F
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2066 && cp <= 0x2069);
    }
}
