package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DeltaGramChatStats;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * Local "chat wrapped": totals, top senders, emoji, activity by hour,
 * media breakdown and the longest pause. Everything is computed from the
 * on-device message database, nothing leaves the phone.
 */
public class DeltaGramChatStatsActivity extends BaseFragment {

    private static final String ARG_DIALOG_ID = "dialog_id";
    private static final int MENU_SHARE = 1;

    private long dialogId;
    private String dialogTitle;
    private ScrollView scrollView;
    private LinearLayout contentView;
    private TextView loadingView;
    private DeltaGramChatStats stats;

    private DeltaGramChatStats remoteStats;
    private DeltaGramChatStats.Builder remoteBuilder;
    private TextView heroCountView;
    private TextView heroPeriodView;
    private TextView progressView;
    private String historyStatus;
    private int oldestLoadedId;
    private boolean remoteStarted;
    private boolean remoteDone;
    private boolean remoteStopped;
    private int floodRetries;
    private int pagesSinceRender;
    private int pagesLoaded;

    private static final HashMap<Long, DeltaGramChatStats> sCache = new HashMap<>();
    private static final HashMap<Long, Long> sCacheTime = new HashMap<>();
    private static final long CACHE_TTL = 10 * 60 * 1000L;

    public DeltaGramChatStatsActivity(Bundle args) {
        super(args);
    }

    public static DeltaGramChatStatsActivity of(long dialogId) {
        final Bundle args = new Bundle();
        args.putLong(ARG_DIALOG_ID, dialogId);
        return new DeltaGramChatStatsActivity(args);
    }

    @Override
    public boolean onFragmentCreate() {
        final Bundle args = getArguments();
        if (args != null) {
            dialogId = args.getLong(ARG_DIALOG_ID, 0);
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        dialogTitle = resolveTitle();

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Итоги чата");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SHARE) {
                    shareStats();
                }
            }
        });
        actionBar.createMenu().addItem(MENU_SHARE, R.drawable.msg_share);

        final FrameLayout root = new FrameLayout(context);
        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        contentView = new LinearLayout(context);
        contentView.setOrientation(LinearLayout.VERTICAL);
        contentView.setPadding(dp(16), dp(8), dp(16), dp(28));
        scrollView.addView(contentView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        loadingView = new TextView(context);
        loadingView.setText("Считаем статистику…");
        loadingView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        loadingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        root.addView(loadingView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = root;
        loadStats();
        return root;
    }

    private void loadStats() {
        getMessagesStorage().getChatStats(dialogId, result -> {
            if (getParentActivity() == null || result == null) {
                return;
            }
            stats = result;
            final DeltaGramChatStats cached = sCache.get(dialogId);
            final Long cachedAt = sCacheTime.get(dialogId);
            if (cached != null && cached.scannedMessages > 0 && cachedAt != null
                    && System.currentTimeMillis() - cachedAt < CACHE_TTL) {
                stats = cached;
                historyStatus = "Учтено " + formatNumber(cached.scannedMessages) + " сообщений истории";
                buildContent();
                if (loadingView != null) {
                    loadingView.setVisibility(View.GONE);
                }
                return;
            }
            buildContent();
            if (loadingView != null) {
                loadingView.setVisibility(View.GONE);
            }
            startHistoryLoad();
        });
    }

    /* ------------------------------------------------------------------ */
    /* Full history                                                        */
    /* ------------------------------------------------------------------ */

    private void startHistoryLoad() {
        if (remoteStarted || remoteDone || getParentActivity() == null || dialogId == 0) {
            return;
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        if (stats != null && stats.dbOldestMid > 0 && stats.dbOldestMid <= 1
                && stats.totalMessages <= DeltaGramChatStats.MAX_SCANNED) {
            // The local database already keeps the beginning of the chat.
            return;
        }
        remoteStarted = true;
        remoteBuilder = new DeltaGramChatStats.Builder(
                UserConfig.getInstance(getCurrentAccount()).clientUserId, dialogId);
        remoteBuilder.stats.fromServer = true;
        oldestLoadedId = 0;
        pagesSinceRender = 0;
        pagesLoaded = 0;
        setProgress(progressText());
        requestHistoryPage();
    }

    private void requestHistoryPage() {
        if (remoteDone || remoteStopped || getParentActivity() == null) {
            return;
        }
        final TLRPC.InputPeer peer = getMessagesController().getInputPeer(dialogId);
        if (peer == null) {
            finishHistoryLoad("История недоступна");
            return;
        }
        final TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
        request.peer = peer;
        request.limit = 100;
        request.offset_id = oldestLoadedId;
        getConnectionsManager().sendRequest(request, (response, error) -> {
            if (remoteStopped || getParentActivity() == null) {
                return;
            }
            if (error != null) {
                if (error.text != null && error.text.startsWith("FLOOD_WAIT_") && floodRetries < 4) {
                    int seconds = 5;
                    try {
                        seconds = Integer.parseInt(error.text.substring("FLOOD_WAIT_".length()));
                    } catch (Exception ignore) {
                    }
                    floodRetries++;
                    final int delay = Math.min(Math.max(seconds, 1), 60);
                    setProgress("Сервер просит подождать " + delay + " с…");
                    AndroidUtilities.runOnUIThread(this::requestHistoryPage, delay * 1000L + 300L);
                    return;
                }
                finishHistoryLoad("Не удалось загрузить историю");
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                finishHistoryLoad("Не удалось загрузить историю");
                return;
            }
            final TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
            if (!result.users.isEmpty()) {
                getMessagesController().putUsers(result.users, false);
            }
            if (!result.chats.isEmpty()) {
                getMessagesController().putChats(result.chats, false);
            }

            int added = 0;
            int pageOldestId = 0;
            for (int i = 0; i < result.messages.size(); i++) {
                final TLRPC.Message message = result.messages.get(i);
                if (message == null || message.date <= 0 || message.id <= 0) {
                    continue;
                }
                remoteBuilder.add(message, message.date);
                added++;
                pageOldestId = message.id;
            }
            remoteStats = remoteBuilder.build();
            if (result.count > remoteStats.totalMessages) {
                remoteStats.totalMessages = result.count;
            }
            if (added == 0) {
                finishHistoryLoad(null);
                return;
            }
            if (pageOldestId != 0) {
                oldestLoadedId = pageOldestId;
            }
            floodRetries = 0;
            pagesLoaded++;
            stats = remoteStats;
            updateHeroLive();
            pagesSinceRender++;
            if (pagesSinceRender >= 5 || heroCountView == null) {
                rebuildPreservingScroll();
                pagesSinceRender = 0;
            }
            setProgress(progressText());

            if (added == 0 || oldestLoadedId <= 1
                    || remoteStats.scannedMessages >= DeltaGramChatStats.MAX_SCANNED
                    || (result.count > 0 && remoteStats.scannedMessages >= result.count)
                    || pagesLoaded >= 200) {
                finishHistoryLoad(null);
                return;
            }
            AndroidUtilities.runOnUIThread(this::requestHistoryPage, 350);
        });
    }

    private void finishHistoryLoad(String note) {
        remoteDone = true;
        if (note != null) {
            historyStatus = note;
        }
        if (remoteStats != null && remoteStats.scannedMessages > 0) {
            stats = remoteStats;
            stats.truncated = stats.totalMessages > stats.scannedMessages;
            if (sCache.size() > 20) {
                sCache.clear();
                sCacheTime.clear();
            }
            sCache.put(dialogId, remoteStats);
            sCacheTime.put(dialogId, System.currentTimeMillis());
        }
        if (getParentActivity() == null) {
            return;
        }
        rebuildPreservingScroll();
        setProgress(progressText());
    }

    private void rebuildPreservingScroll() {
        final int scrollY = scrollView != null ? scrollView.getScrollY() : 0;
        buildContent();
        if (scrollView != null) {
            scrollView.post(() -> scrollView.scrollTo(0, scrollY));
        }
    }

    private void updateHeroLive() {
        if (heroCountView != null && stats != null) {
            heroCountView.setText(formatNumber(stats.scannedMessages));
        }
        if (heroPeriodView != null) {
            heroPeriodView.setText(periodText());
        }
    }

    private void setProgress(String text) {
        if (progressView != null) {
            progressView.setText(text);
            progressView.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }
    }

    private String progressText() {
        if (historyStatus != null) {
            return historyStatus;
        }
        if (remoteDone) {
            if (remoteStats != null && remoteStats.scannedMessages > 0) {
                return "Учтено " + formatNumber(remoteStats.scannedMessages) + " сообщений истории";
            }
            return "";
        }
        if (remoteStats != null) {
            return "Загружаем историю… уже " + formatNumber(remoteStats.scannedMessages) + " сообщений";
        }
        if (remoteStarted) {
            return "Загружаем историю чата…";
        }
        return "";
    }

    @Override
    public void onFragmentDestroy() {
        remoteStopped = true;
        super.onFragmentDestroy();
    }

    /* ------------------------------------------------------------------ */
    /* Content                                                             */
    /* ------------------------------------------------------------------ */

    private void buildContent() {
        if (contentView == null || getParentActivity() == null) {
            return;
        }
        contentView.removeAllViews();
        if (stats == null || stats.scannedMessages == 0) {
            final TextView empty = new TextView(getParentActivity());
            if (historyStatus != null) {
                empty.setText(historyStatus);
            } else if (remoteStarted && !remoteDone) {
                empty.setText("Загружаем историю чата…");
            } else {
                empty.setText("Пока нечего считать — в этом чате нет сохранённых сообщений.");
            }
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            empty.setPadding(dp(8), dp(40), dp(8), dp(8));
            contentView.addView(empty);
            progressView = empty;
            return;
        }
        addHero();
        addTopSenders();
        addEmojis();
        addHours();
        addMedia();
        addPause();
        addFooter();
    }

    private void addHero() {
        final Context context = getParentActivity();
        final LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(18), dp(20), dp(18));
        final GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF2F5D8A, 0xFF1B3550});
        bg.setCornerRadius(dp(20));
        hero.setBackground(bg);

        final TextView cap = new TextView(context);
        cap.setText("ИТОГИ ЧАТА");
        cap.setTextColor(0xCCFFFFFF);
        cap.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        cap.setTypeface(AndroidUtilities.bold());
        cap.setLetterSpacing(0.08f);
        hero.addView(cap);

        final TextView name = new TextView(context);
        name.setText(dialogTitle);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        name.setTypeface(AndroidUtilities.bold());
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(6), 0, dp(10));
        hero.addView(name);

        final TextView total = new TextView(context);
        total.setText(formatNumber(stats.scannedMessages));
        total.setTextColor(Color.WHITE);
        total.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 42);
        total.setTypeface(AndroidUtilities.bold());
        hero.addView(total);
        heroCountView = total;

        final TextView totalLabel = new TextView(context);
        String label = "сообщений";
        if (stats.totalMessages > stats.scannedMessages) {
            label += (stats.fromServer ? " • всего в чате " : " • в базе ")
                    + formatNumber(stats.totalMessages);
        }
        totalLabel.setText(label);
        totalLabel.setTextColor(0xCCFFFFFF);
        totalLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hero.addView(totalLabel);

        final TextView period = new TextView(context);
        period.setText(periodText());
        period.setTextColor(0x99FFFFFF);
        period.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        period.setPadding(0, dp(10), 0, 0);
        hero.addView(period);
        heroPeriodView = period;

        progressView = new TextView(context);
        progressView.setText(progressText());
        progressView.setTextColor(0x99FFFFFF);
        progressView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        progressView.setPadding(0, dp(8), 0, 0);
        progressView.setVisibility(TextUtils.isEmpty(progressText()) ? View.GONE : View.VISIBLE);
        hero.addView(progressView);

        contentView.addView(hero, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addTopSenders() {
        if (stats.topSenders.isEmpty()) {
            return;
        }
        contentView.addView(section("КТО ПИСАЛ ЧАЩЕ"));
        final LinearLayout card = card();
        final int max = Math.max(1, stats.topSenders.get(0).count);
        for (int i = 0; i < stats.topSenders.size(); i++) {
            final DeltaGramChatStats.SenderStat sender = stats.topSenders.get(i);
            final LinearLayout row = new LinearLayout(getParentActivity());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            final TextView name = new TextView(getParentActivity());
            name.setText(nameForSender(sender.senderId));
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final TextView count = new TextView(getParentActivity());
            count.setText(formatNumber(sender.count));
            count.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            row.addView(count);

            card.addView(row);

            final LinearLayout bar = new LinearLayout(getParentActivity());
            bar.setOrientation(LinearLayout.HORIZONTAL);
            final View fill = new View(getParentActivity());
            final GradientDrawable fillBg = new GradientDrawable();
            fillBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            fillBg.setCornerRadius(dp(3));
            fill.setBackground(fillBg);
            final float ratio = Math.max(0.03f, sender.count / (float) max);
            bar.addView(fill, new LinearLayout.LayoutParams(0, dp(6), ratio));
            final View rest = new View(getParentActivity());
            bar.addView(rest, new LinearLayout.LayoutParams(0, dp(6), Math.max(0.03f, 1f - ratio)));
            card.addView(bar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));
        }
    }

    private void addEmojis() {
        if (stats.topEmojis.isEmpty()) {
            return;
        }
        contentView.addView(section("ТОП ЭМОДЗИ"));
        final LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < stats.topEmojis.size(); i++) {
            final DeltaGramChatStats.EmojiStat emoji = stats.topEmojis.get(i);
            final LinearLayout column = new LinearLayout(getParentActivity());
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);

            final TextView symbol = new TextView(getParentActivity());
            symbol.setText(emoji.emoji);
            symbol.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
            column.addView(symbol);

            final TextView count = new TextView(getParentActivity());
            count.setText("×" + formatNumber(emoji.count));
            count.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            column.addView(count);

            card.addView(column, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void addHours() {
        contentView.addView(section("КОГДА ВЫ АКТИВНЫ"));
        final LinearLayout card = card();

        final HoursView hoursView = new HoursView(getParentActivity());
        card.addView(hoursView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(110)));

        final int peak = stats.peakHour();
        final TextView peakView = new TextView(getParentActivity());
        peakView.setText("Пик активности: " + String.format(Locale.US, "%02d:00", peak)
                + "–" + String.format(Locale.US, "%02d:00", (peak + 1) % 24));
        peakView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        peakView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        peakView.setPadding(0, dp(8), 0, 0);
        card.addView(peakView);
    }

    private void addMedia() {
        final int media = stats.mediaTotal();
        if (media == 0 && stats.outMessages == 0 && stats.serviceMessages == 0) {
            return;
        }
        contentView.addView(section("ЧТО ОТПРАВЛЯЛИ"));
        final LinearLayout card = card();
        addStatRow(card, "Ваших сообщений", stats.outMessages);
        addStatRow(card, "Фото", stats.photos);
        addStatRow(card, "Видео", stats.videos);
        addStatRow(card, "Голосовые", stats.voice);
        addStatRow(card, "Стикеры", stats.stickers);
        addStatRow(card, "GIF", stats.gifs);
        addStatRow(card, "Файлы", stats.files);
        addStatRow(card, "Служебные", stats.serviceMessages);
    }

    private void addPause() {
        if (stats.longestPauseSeconds < 3600 || stats.longestPauseStartDate == 0) {
            return;
        }
        contentView.addView(section("САМАЯ ДОЛГАЯ ПАУЗА"));
        final LinearLayout card = card();

        final TextView big = new TextView(getParentActivity());
        big.setText(formatDuration(stats.longestPauseSeconds));
        big.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        big.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        big.setTypeface(AndroidUtilities.bold());
        card.addView(big);

        final TextView range = new TextView(getParentActivity());
        range.setText("с " + dateText(stats.longestPauseStartDate) + " по " + dateText(stats.longestPauseEndDate));
        range.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        range.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        range.setPadding(0, dp(4), 0, 0);
        card.addView(range);

        final TextView who = new TextView(getParentActivity());
        who.setText("Последним перед паузой писал(а): " + nameForSender(stats.longestPauseFirstSenderId));
        who.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        who.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        who.setPadding(0, dp(6), 0, 0);
        card.addView(who);
    }

    private void addFooter() {
        final TextView footer = new TextView(getParentActivity());
        footer.setText("Собрано локально • DeltaGram");
        footer.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        footer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(22), 0, 0);
        contentView.addView(footer);
    }

    /* ------------------------------------------------------------------ */
    /* Sharing                                                             */
    /* ------------------------------------------------------------------ */

    private void shareStats() {
        if (stats == null || getParentActivity() == null) {
            if (getParentActivity() != null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Ещё считаем статистику…").show();
            }
            return;
        }
        final Bitmap bitmap = renderShareBitmap();
        if (bitmap == null) {
            return;
        }
        try {
            final File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            final File file = new File(dir, "dg_chat_stats_" + dialogId + ".png");
            final FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            bitmap.recycle();

            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            if (Build.VERSION.SDK_INT >= 24) {
                intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                        getParentActivity(),
                        ApplicationLoader.getApplicationId() + ".provider", file));
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
            }
            getParentActivity().startActivityForResult(
                    Intent.createChooser(intent, "Поделиться итогами"), 500);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private Bitmap renderShareBitmap() {
        final int width = 1080;
        final int height = 1920;
        try {
            final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);

            final Paint background = new Paint();
            background.setShader(new LinearGradient(0, 0, width, height,
                    0xFF15283A, 0xFF2E5D8C, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, background);

            final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(AndroidUtilities.bold());
            paint.setColor(Color.WHITE);

            paint.setTextSize(34);
            paint.setLetterSpacing(0.12f);
            paint.setColor(0xFF8FB8D8);
            canvas.drawText("ИТОГИ ЧАТА", 72, 150, paint);
            paint.setLetterSpacing(0);

            paint.setColor(Color.WHITE);
            paint.setTextSize(60);
            drawEllipsized(canvas, dialogTitle, 72, 250, width - 144, paint);

            paint.setTextSize(190);
            canvas.drawText(formatNumber(stats.scannedMessages), 72, 480, paint);

            paint.setTextSize(40);
            paint.setColor(0xFFB9D4EA);
            canvas.drawText("сообщений", 72, 540, paint);

            paint.setTextSize(30);
            canvas.drawText(periodText(), 72, 596, paint);

            int y = 740;
            paint.setTextSize(30);
            paint.setColor(0xFF8FB8D8);
            canvas.drawText("КТО ПИСАЛ ЧАЩЕ", 72, y, paint);
            y += 64;
            paint.setTextSize(40);
            paint.setColor(Color.WHITE);
            for (int i = 0; i < stats.topSenders.size(); i++) {
                final DeltaGramChatStats.SenderStat sender = stats.topSenders.get(i);
                drawEllipsized(canvas,
                        (i + 1) + ". " + nameForSender(sender.senderId) + " — " + formatNumber(sender.count),
                        72, y, width - 144, paint);
                y += 60;
            }

            y += 40;
            paint.setTextSize(30);
            paint.setColor(0xFF8FB8D8);
            canvas.drawText("ТОП ЭМОДЗИ", 72, y, paint);
            y += 74;
            final StringBuilder emojiLine = new StringBuilder();
            for (int i = 0; i < stats.topEmojis.size(); i++) {
                if (i > 0) {
                    emojiLine.append("   ");
                }
                emojiLine.append(stats.topEmojis.get(i).emoji);
            }
            paint.setTextSize(58);
            paint.setColor(Color.WHITE);
            canvas.drawText(emojiLine.toString(), 72, y, paint);

            y += 130;
            paint.setTextSize(30);
            paint.setColor(0xFF8FB8D8);
            canvas.drawText("ЧАС ПИКА", 72, y, paint);
            y += 64;
            paint.setTextSize(44);
            paint.setColor(Color.WHITE);
            canvas.drawText(String.format(Locale.US, "%02d:00 – %02d:00",
                    stats.peakHour(), (stats.peakHour() + 1) % 24), 72, y, paint);

            if (stats.longestPauseSeconds >= 3600) {
                y += 130;
                paint.setTextSize(30);
                paint.setColor(0xFF8FB8D8);
                canvas.drawText("САМАЯ ДОЛГАЯ ПАУЗА", 72, y, paint);
                y += 64;
                paint.setTextSize(44);
                paint.setColor(Color.WHITE);
                canvas.drawText(formatDuration(stats.longestPauseSeconds), 72, y, paint);
                y += 46;
                paint.setTextSize(28);
                paint.setColor(0xFFB9D4EA);
                canvas.drawText(dateText(stats.longestPauseStartDate)
                        + " — " + dateText(stats.longestPauseEndDate), 72, y, paint);
            }

            paint.setTextSize(26);
            paint.setColor(0xFF7FA6C4);
            canvas.drawText("DeltaGram • собрано локально", 72, height - 72, paint);
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void drawEllipsized(Canvas canvas, CharSequence text, float x, float y,
                                       float maxWidth, TextPaint paint) {
        if (text == null) {
            return;
        }
        canvas.drawText(TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString(),
                x, y, paint);
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private String resolveTitle() {
        final int account = getCurrentAccount();
        if (dialogId > 0) {
            final TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                if (user.self) {
                    return "Избранное";
                }
                return ContactsController.formatName(user);
            }
            return "Чат";
        }
        final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        return chat != null ? chat.title : "Чат";
    }

    private String nameForSender(long senderId) {
        if (senderId == 0) {
            return "Кто-то";
        }
        if (senderId == UserConfig.getInstance(getCurrentAccount()).clientUserId) {
            return "Вы";
        }
        if (senderId > 0) {
            final TLRPC.User user = getMessagesController().getUser(senderId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
            return "Пользователь";
        }
        final TLRPC.Chat chat = getMessagesController().getChat(-senderId);
        return chat != null ? chat.title : "Чат";
    }

    private String periodText() {
        if (stats.firstMessageDate == 0 || stats.lastMessageDate == 0) {
            return "";
        }
        return "с " + dateText(stats.firstMessageDate) + " по " + dateText(stats.lastMessageDate);
    }

    private static String dateText(long seconds) {
        try {
            return new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date(seconds * 1000L));
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatNumber(int value) {
        return String.format(Locale.US, "%,d", value).replace(',', ' ');
    }

    private static String formatDuration(long seconds) {
        final long days = seconds / 86400L;
        if (days > 0) {
            return days + " " + plural((int) days, "день", "дня", "дней");
        }
        final long hours = seconds / 3600L;
        if (hours > 0) {
            return hours + " " + plural((int) hours, "час", "часа", "часов");
        }
        final long minutes = Math.max(1, seconds / 60L);
        return minutes + " " + plural((int) minutes, "минута", "минуты", "минут");
    }

    private static String plural(int value, String one, String few, String many) {
        final int m10 = value % 10;
        final int m100 = value % 100;
        if (m10 == 1 && m100 != 11) {
            return one;
        }
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) {
            return few;
        }
        return many;
    }

    private TextView section(String title) {
        final TextView view = new TextView(getParentActivity());
        view.setText(title);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTypeface(AndroidUtilities.bold());
        view.setLetterSpacing(0.06f);
        view.setPadding(dp(4), dp(20), dp(4), dp(8));
        return view;
    }

    private LinearLayout card() {
        final LinearLayout card = new LinearLayout(getParentActivity());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(Theme.createRoundRectDrawable(dp(16),
                Theme.getColor(Theme.key_windowBackgroundWhite)));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        contentView.addView(card, lp);
        return card;
    }

    private void addStatRow(LinearLayout card, String label, int value) {
        if (value == 0) {
            return;
        }
        final LinearLayout row = new LinearLayout(getParentActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));

        final TextView name = new TextView(getParentActivity());
        name.setText(label);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        row.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView count = new TextView(getParentActivity());
        count.setText(formatNumber(value));
        count.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        count.setTypeface(AndroidUtilities.bold());
        row.addView(count);

        card.addView(row);
    }

    private int dp(int value) {
        return AndroidUtilities.dp(value);
    }

    private class HoursView extends View {
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        HoursView(Context context) {
            super(context);
            barPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            labelPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            labelPaint.setTextSize(dp(9));
            labelPaint.setTypeface(Typeface.DEFAULT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (stats == null) {
                return;
            }
            final int width = getWidth();
            final int height = getHeight();
            final int top = dp(6);
            final int bottom = height - dp(16);
            int max = 1;
            for (int i = 0; i < 24; i++) {
                max = Math.max(max, stats.byHour[i]);
            }
            final int gap = dp(2);
            final float barWidth = (width - gap * 23) / 24f;
            for (int i = 0; i < 24; i++) {
                final float barHeight = (bottom - top) * (stats.byHour[i] / (float) max);
                final float left = i * (barWidth + gap);
                canvas.drawRoundRect(left, bottom - barHeight, left + barWidth, bottom,
                        dp(2), dp(2), barPaint);
            }
            final int[] marks = {0, 6, 12, 18};
            for (int mark : marks) {
                final String text = String.format(Locale.US, "%02d", mark);
                final float x = mark * (barWidth + gap);
                canvas.drawText(text, Math.min(x, width - labelPaint.measureText(text)),
                        height - dp(2), labelPaint);
            }
        }
    }
}
