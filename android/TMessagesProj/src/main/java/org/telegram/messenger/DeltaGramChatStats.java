package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.TimeZone;

/**
 * Local "chat wrapped" statistics collected from the messages database and,
 * when needed, from the chat history loaded directly from the server.
 *
 * Nothing is sent anywhere: the numbers are computed on the device.
 */
public class DeltaGramChatStats {

    public static final int MAX_SCANNED = 10000;

    public long dialogId;
    /** Number of messages known for this dialog (local database or server total). */
    public int totalMessages;
    /** Number of messages the numbers below are computed from. */
    public int scannedMessages;
    /** True when not all known messages were scanned. */
    public boolean truncated;
    /** True when the numbers were built from the server history. */
    public boolean fromServer;
    /** Oldest message id stored locally, used to detect a complete local history. */
    public int dbOldestMid;

    public long firstMessageDate;
    public long lastMessageDate;

    public int outMessages;
    public int serviceMessages;

    public int photos;
    public int videos;
    public int voice;
    public int stickers;
    public int gifs;
    public int files;

    public final int[] byHour = new int[24];

    public final ArrayList<SenderStat> topSenders = new ArrayList<>();
    public final ArrayList<EmojiStat> topEmojis = new ArrayList<>();

    public long longestPauseSeconds;
    public long longestPauseStartDate;
    public long longestPauseEndDate;
    public long longestPauseFirstSenderId;
    public long longestPauseSecondSenderId;

    public interface Callback {
        void run(DeltaGramChatStats stats);
    }

    public static class SenderStat {
        public final long senderId;
        public int count;

        SenderStat(long senderId) {
            this.senderId = senderId;
        }
    }

    public static class EmojiStat {
        public final String emoji;
        public int count;

        EmojiStat(String emoji) {
            this.emoji = emoji;
        }
    }

    public int peakHour() {
        int best = 0;
        for (int i = 1; i < 24; i++) {
            if (byHour[i] > byHour[best]) {
                best = i;
            }
        }
        return best;
    }

    public int mediaTotal() {
        return photos + videos + voice + stickers + gifs + files;
    }

    /**
     * Incrementally collects the numbers. Messages must be added newest first,
     * which is how both the database query and the server history return them.
     */
    public static class Builder {

        public final DeltaGramChatStats stats;
        private final HashMap<Long, SenderStat> senders = new HashMap<>();
        private final HashMap<String, EmojiStat> emojis = new HashMap<>();
        private final long selfId;
        private long prevDate;
        private long prevSender;

        public Builder(long selfId, long dialogId) {
            this.selfId = selfId;
            this.stats = new DeltaGramChatStats();
            this.stats.dialogId = dialogId;
        }

        public void add(TLRPC.Message message, int date) {
            if (message == null || date <= 0) {
                return;
            }
            stats.scannedMessages++;
            if (stats.lastMessageDate == 0) {
                stats.lastMessageDate = date;
            }
            if (stats.firstMessageDate == 0 || date < stats.firstMessageDate) {
                stats.firstMessageDate = date;
            }

            long senderId;
            if (message.out) {
                senderId = selfId;
                stats.outMessages++;
            } else if (message.from_id != null) {
                senderId = DialogObject.getPeerDialogId(message.from_id);
            } else {
                senderId = stats.dialogId;
            }
            if (senderId == 0) {
                senderId = stats.dialogId;
            }

            if (message.action != null) {
                stats.serviceMessages++;
            } else {
                SenderStat senderStat = senders.get(senderId);
                if (senderStat == null) {
                    senders.put(senderId, senderStat = new SenderStat(senderId));
                }
                senderStat.count++;
            }

            classifyMedia(message, stats);
            collectEmojis(message.message, emojis);

            final int offset = TimeZone.getDefault().getOffset(date * 1000L) / 1000;
            final int hour = (int) (((date + offset) / 3600L) % 24L);
            if (hour >= 0 && hour < 24) {
                stats.byHour[hour]++;
            }

            if (prevDate > 0 && prevDate > date) {
                final long gap = prevDate - date;
                if (gap > stats.longestPauseSeconds) {
                    stats.longestPauseSeconds = gap;
                    stats.longestPauseStartDate = date;
                    stats.longestPauseEndDate = prevDate;
                    stats.longestPauseFirstSenderId = senderId;
                    stats.longestPauseSecondSenderId = prevSender;
                }
            }
            prevDate = date;
            prevSender = senderId;
        }

        public DeltaGramChatStats build() {
            final ArrayList<SenderStat> senderList = new ArrayList<>(senders.values());
            Collections.sort(senderList, (a, b) -> Integer.compare(b.count, a.count));
            stats.topSenders.clear();
            for (int i = 0; i < Math.min(5, senderList.size()); i++) {
                stats.topSenders.add(senderList.get(i));
            }

            final ArrayList<EmojiStat> emojiList = new ArrayList<>(emojis.values());
            Collections.sort(emojiList, (a, b) -> Integer.compare(b.count, a.count));
            stats.topEmojis.clear();
            for (int i = 0; i < Math.min(5, emojiList.size()); i++) {
                stats.topEmojis.add(emojiList.get(i));
            }
            return stats;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Media classification                                                */
    /* ------------------------------------------------------------------ */

    public static void classifyMedia(TLRPC.Message message, DeltaGramChatStats stats) {
        if (message == null || message.media == null) {
            return;
        }
        if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
            stats.photos++;
            return;
        }
        if (!(message.media instanceof TLRPC.TL_messageMediaDocument)) {
            return;
        }
        final TLRPC.Document document = ((TLRPC.TL_messageMediaDocument) message.media).document;
        if (document == null) {
            stats.files++;
            return;
        }
        boolean sticker = false;
        boolean gif = false;
        boolean voice = false;
        boolean video = false;
        if (document.attributes != null) {
            for (int i = 0; i < document.attributes.size(); i++) {
                final TLRPC.DocumentAttribute attribute = document.attributes.get(i);
                if (attribute instanceof TLRPC.TL_documentAttributeSticker) {
                    sticker = true;
                } else if (attribute instanceof TLRPC.TL_documentAttributeAnimated) {
                    gif = true;
                } else if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    video = true;
                } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                    if (((TLRPC.TL_documentAttributeAudio) attribute).voice) {
                        voice = true;
                    }
                }
            }
        }
        if (sticker) {
            stats.stickers++;
        } else if (gif) {
            stats.gifs++;
        } else if (voice) {
            stats.voice++;
        } else if (video) {
            stats.videos++;
        } else {
            stats.files++;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Emoji scanning                                                      */
    /* ------------------------------------------------------------------ */

    public static void collectEmojis(String text, HashMap<String, EmojiStat> out) {
        if (text == null || text.length() == 0) {
            return;
        }
        int i = 0;
        while (i < text.length()) {
            final int cp = text.codePointAt(i);
            final int len = Character.charCount(cp);
            if (isEmojiCodePoint(cp)) {
                final StringBuilder sb = new StringBuilder();
                sb.appendCodePoint(cp);
                int j = i + len;
                while (j < text.length()) {
                    final int next = text.codePointAt(j);
                    if (next == 0xFE0F || next == 0x200D || next == 0x20E3 || isEmojiCodePoint(next)) {
                        sb.appendCodePoint(next);
                        j += Character.charCount(next);
                    } else {
                        break;
                    }
                }
                final String key = sb.toString();
                EmojiStat stat = out.get(key);
                if (stat == null) {
                    stat = new EmojiStat(key);
                    out.put(key, stat);
                }
                stat.count++;
                i = j;
            } else {
                i += len;
            }
        }
    }

    private static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || cp == 0x2764
                || cp == 0x203C
                || cp == 0x2049
                || cp == 0x2122
                || cp == 0x2139;
    }
}
