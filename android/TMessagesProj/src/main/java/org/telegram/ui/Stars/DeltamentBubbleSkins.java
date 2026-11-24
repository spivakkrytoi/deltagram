package org.telegram.ui.Stars;

import org.telegram.messenger.MessagesController;

/**
 * Visual skins for the profile gift bubbles. Purely cosmetic: the skin changes
 * the ring, glow and particle colour, the gift sticker itself stays the same.
 */
public final class DeltamentBubbleSkins {

    public static final int GLASS = 0;
    public static final int NEON = 1;
    public static final int GOLD = 2;
    public static final int ICE = 3;
    public static final int FIRE = 4;
    public static final int MONO = 5;

    private static final String PREF_KEY = "dg_bubble_skin";

    private static final String[] NAMES = {
            "Стекло", "Неон", "Золото", "Лёд", "Огонь", "Моно"
    };

    private static int cached = -1;

    private DeltamentBubbleSkins() {
    }

    public static int get() {
        if (cached < 0) {
            cached = MessagesController.getGlobalMainSettings().getInt(PREF_KEY, GLASS);
            if (cached < 0 || cached >= NAMES.length) {
                cached = GLASS;
            }
        }
        return cached;
    }

    public static void set(int skin) {
        if (skin < 0 || skin >= NAMES.length) {
            skin = GLASS;
        }
        cached = skin;
        MessagesController.getGlobalMainSettings().edit().putInt(PREF_KEY, skin).apply();
    }

    public static String[] names() {
        return NAMES.clone();
    }

    public static String name(int skin) {
        if (skin < 0 || skin >= NAMES.length) {
            skin = GLASS;
        }
        return NAMES[skin];
    }

    public static boolean isDefault(int skin) {
        return skin <= GLASS;
    }

    /** Ring / glow / particle accent colour for the skin. */
    public static int accent(int skin) {
        switch (skin) {
            case NEON:
                return 0xFF3BE8FF;
            case GOLD:
                return 0xFFFFD36A;
            case ICE:
                return 0xFFB8F0FF;
            case FIRE:
                return 0xFFFF7A2F;
            case MONO:
                return 0xFFE6E6E6;
            case GLASS:
            default:
                return 0xFFFFFFFF;
        }
    }

    /** How thick the glowing ring should be drawn, in dp. */
    public static float ringWidth(int skin) {
        switch (skin) {
            case NEON:
                return 1.6f;
            case GOLD:
                return 1.8f;
            case ICE:
                return 1.4f;
            case FIRE:
                return 1.7f;
            case MONO:
                return 1.2f;
            case GLASS:
            default:
                return 0f;
        }
    }
}
