package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.SwipeGestureSettingsView;
import org.telegram.ui.Cells.AppIconsSelectorCell;
import org.telegram.ui.Wallet.DeltamentCardSettingsActivity;
import org.telegram.ui.Wallet.DeltamentDomainsActivity;
import org.telegram.ui.Wallet.DeltamentMusicSheet;

/**
 * DeltaGram settings.
 *
 * DeltaGram preferences: General / Appearance / Chats / Other.
 * Every row is wired to a real DeltaGram or Telegram setting.
 */
public class DeltaGramSettingsActivity extends BaseFragment {

    public static final int PAGE_MAIN = 0;
    public static final int PAGE_GENERAL = 1;
    public static final int PAGE_APPEARANCE = 2;
    public static final int PAGE_CHATS = 3;
    // Kept for old deep links; badges are no longer a settings page.
    public static final int PAGE_BADGES = 4;
    public static final int PAGE_OTHER = 5;

    private static final String EXTRA_PAGE = "deltagram_page";

    private final int currentAccount = UserConfig.selectedAccount;
    private int page;
    private TextView lastValueView;
    private TextView profileTabValueView;

    public DeltaGramSettingsActivity() {
        this(PAGE_MAIN);
    }

    public DeltaGramSettingsActivity(int page) {
        this.page = page;
    }

    public static DeltaGramSettingsActivity of(int page) {
        return new DeltaGramSettingsActivity(page);
    }

    @Override
    public boolean onFragmentCreate() {
        if (getArguments() != null) {
            page = getArguments().getInt(EXTRA_PAGE, page);
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(titleForPage());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        fragmentView = scroll;

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(32));
        scroll.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (page == PAGE_MAIN) {
            buildHeader(content, context);
        }

        switch (page) {
            case PAGE_GENERAL:
                buildGeneral(content);
                break;
            case PAGE_APPEARANCE:
                buildAppearance(content);
                break;
            case PAGE_CHATS:
                buildChats(content);
                break;
            case PAGE_BADGES:
                buildMain(content);
                break;
            case PAGE_OTHER:
                buildOther(content);
                break;
            case PAGE_MAIN:
            default:
                buildMain(content);
                break;
        }

        TextView footer = new TextView(context);
        footer.setText("DeltaGram · " + BuildVariant.versionLine());
        footer.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        footer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(24), 0, dp(8));
        content.addView(footer);
        if (page == PAGE_MAIN || page == PAGE_OTHER) {
            TextView attribution = new TextView(context);
            attribution.setText("Частина функцій DeltaGram основана на exteraGram · github.com/exteraSquad/exteraGram");
            attribution.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            attribution.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            attribution.setGravity(Gravity.CENTER);
            attribution.setPadding(dp(21), 0, dp(21), dp(12));
            attribution.setOnClickListener(v ->
                    Browser.openUrl(getParentActivity(), "https://github.com/exteraSquad/exteraGram"));
            content.addView(attribution);
        }

        return fragmentView;
    }

    private String titleForPage() {
        switch (page) {
            case PAGE_GENERAL:
                return "Основные";
            case PAGE_APPEARANCE:
                return "Внешний вид";
            case PAGE_CHATS:
                return "Чаты";
            case PAGE_OTHER:
                return "Другое";
            case PAGE_MAIN:
            default:
                return "DeltaGram";
        }
    }

    private void buildHeader(LinearLayout content, Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(21), dp(18), dp(21), dp(4));

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.deltament_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(logo, LayoutHelper.createLinear(56, 56));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);

        TextView title = new TextView(context);
        title.setText("DeltaGram");
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        title.setTypeface(AndroidUtilities.bold());
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        subtitle.setText("DeltaGram edition · build " + org.telegram.messenger.BuildVars.BUILD_VERSION_STRING);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        header.addView(texts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView intro = new TextView(context);
        intro.setText("Все настройки DeltaGram в одном месте. Изменения применяются сразу; для радиуса пузырей новый размер подхватится при следующем открытии чата.");
        intro.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        intro.setLineSpacing(dp(2), 1f);
        intro.setPadding(dp(21), dp(14), dp(21), dp(8));
        content.addView(intro);
    }

    private void buildMain(LinearLayout content) {
        content.addView(section("Персонализация"));
        content.addView(category("Основные", "Удалённые сообщения, приватность, номера", R.drawable.msg_media, PAGE_GENERAL));
        content.addView(category("Внешний вид", "Пузыри, текст, тема", R.drawable.msg_theme, PAGE_APPEARANCE));
        content.addView(category("Чаты", "Свайпы, двойной тап, реакции", R.drawable.msg_discussion, PAGE_CHATS));

        content.addView(section("Аккаунт"));
        content.addView(action("Домены .ton", "Покупка, продажа и привязка домена", R.drawable.settings_gram_24, v ->
                presentFragment(new DeltamentDomainsActivity())));
        content.addView(action("Карты и баланс", "Цвет и TON-адрес публичной карты", R.drawable.wallet_gram_small, v ->
                presentFragment(new DeltamentCardSettingsActivity())));
        content.addView(action("Музыка", "Чарты SoundCloud и плеер DeltaGram", R.drawable.files_music, v ->
                new DeltamentMusicSheet(getParentActivity()).show()));

        content.addView(section("О клиенте"));
        content.addView(category("Другое", "Обновления, ссылки, сброс настроек", R.drawable.msg_fave, PAGE_OTHER));
        content.addView(action("Канал DeltaGram", "Новости и обновления", R.drawable.msg_channel, v ->
                Browser.openUrl(getParentActivity(), "https://t.me/deltagram")));
        content.addView(action("Чат DeltaGram", "Обсуждение и помощь", R.drawable.msg_groups, v ->
                Browser.openUrl(getParentActivity(), "https://t.me/deltagramchat")));
        content.addView(action("Сайт", "dltagram.duckdns.org", R.drawable.msg_language, v ->
                Browser.openUrl(getParentActivity(), "https://dltagram.duckdns.org")));
    }

    private void buildGeneral(LinearLayout content) {
        content.addView(section("Сообщения"));
        addSwitch(content, "Фильтровать Zalgo",
                "Скрывать чрезмерные диакритические знаки и управляющие символы направления в сообщениях",
                prefs().getBoolean("delta_filter_zalgo", true), (view, checked) ->
                        prefs().edit().putBoolean("delta_filter_zalgo", checked).apply());
        addSwitch(content, "Показывать секунды",
                "Отображать время сообщений с секундами",
                prefs().getBoolean("delta_time_with_seconds", false), (view, checked) -> {
                    prefs().edit().putBoolean("delta_time_with_seconds", checked).apply();
                    LocaleController.getInstance().recreateFormatters();
                    NotificationCenter.getInstance(currentAccount).postNotificationName(
                            NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                });
        addSwitch(content, "Сохранять удалённые сообщения",
                "Сообщение, удалённое собеседником, остаётся в чате и в локальной базе",
                SharedConfig.keepDeletedMessages, (view, checked) -> {
                    SharedConfig.keepDeletedMessages = checked;
                    prefs().edit().putBoolean("deltagram_keep_deleted", checked).apply();
                });

        content.addView(section("Приватность"));
        addSwitch(content, "Скрывать номер телефона",
                "В профиле вместо номера будет написано «Скрыто»",
                SharedConfig.hidePhoneNumber, (view, checked) -> {
                    SharedConfig.hidePhoneNumber = checked;
                    prefs().edit().putBoolean("deltagram_hide_phone", checked).apply();
                });
        addSwitch(content, "Показывать ID и DC",
                "Добавляет строку ID и номер дата-центра в профиль",
                SharedConfig.showIdAndDc, (view, checked) -> {
                    SharedConfig.showIdAndDc = checked;
                    prefs().edit().putBoolean("deltagram_show_id", checked).apply();
                });
        // exteraGram General Preferences: choose the first profile media tab.
        addChoice(content, "Вкладка профиля по умолчанию",
                "Открывать медиа или общие группы вместо первой вкладки",
                profileTabLabel(), R.drawable.msg_discussion, this::showProfileTabDialog);
        profileTabValueView = lastValueView;

        content.addView(section("Числа и время"));
        addSwitch(content, "Не сокращать большие числа",
                "Показывать 1 234 567 вместо 1.2M в просмотрах и реакциях",
                SharedConfig.disableNumberRounding, (view, checked) -> {
                    SharedConfig.disableNumberRounding = checked;
                    prefs().edit().putBoolean("deltagram_disable_rounding", checked).apply();
                });
        addAction(content, "Размер текста в чатах",
                "Открыть настройки размера текста Telegram",
                currentTextSizeLabel(), R.drawable.msg_edit, v ->
                        presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
    }

    private void buildAppearance(LinearLayout content) {
        content.addView(section("Сообщения"));
        addBubbleRadius(content);

        content.addView(section("Текст и тема"));
        addAction(content, "Размер текста в чатах",
                "Сейчас: " + currentTextSizeLabel(),
                null, R.drawable.msg_edit, v ->
                        presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        addAction(content, "Тема оформления",
                "Акценты, обои, ночной режим",
                null, R.drawable.msg_theme, v ->
                        presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        content.addView(section("Иконка приложения"));
        // Use Telegram's actual interactive selector with adaptive icon
        // previews, premium locks and selection animation.
        content.addView(new AppIconsSelectorCell(getParentActivity(), this, currentAccount),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 120));
        addAction(content, "Иконки и панели",
                "Настройки интерфейса Telegram",
                null, R.drawable.msg_customize, v ->
                        presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_OTHER)));

    }

    private void buildChats(LinearLayout content) {
        content.addView(section("Список чатов"));
        addChoice(content, "Действие свайпа",
                "Что происходит при свайпе по чату в списке",
                swipeActionLabel(), R.drawable.msg_arrow_forward, this::showSwipeActionDialog);

        content.addView(section("Сообщения"));
        addAction(content, "Двойной тап по сообщению",
                "Быстрая реакция на двойной тап",
                null, R.drawable.msg_reactions, v ->
                        presentFragment(new ReactionsDoubleTapManageActivity()));
        addAction(content, "Настройки чатов",
                "Автозагрузка, звуки и прочее",
                null, R.drawable.msg_discussion, v ->
                        presentFragment(new SettingsActivity()));
    }

    private void buildOther(LinearLayout content) {
        content.addView(section("Обновления"));
        addAction(content, "Проверить обновления",
                "Текущая версия: " + BuildVariant.versionLine(),
                null, R.drawable.msg_download, v -> {
                    if (getParentActivity() instanceof LaunchActivity) {
                        ((LaunchActivity) getParentActivity()).checkAppUpdate(true, null);
                    }
                });

        content.addView(section("Ссылки"));
        addAction(content, "Канал", "@deltagram", null, R.drawable.msg_channel, v ->
                Browser.openUrl(getParentActivity(), "https://t.me/deltagram"));
        addAction(content, "Чат", "@deltagramchat", null, R.drawable.msg_groups, v ->
                Browser.openUrl(getParentActivity(), "https://t.me/deltagramchat"));
        addAction(content, "Сайт", "dltagram.duckdns.org", null, R.drawable.msg_language, v ->
                Browser.openUrl(getParentActivity(), "https://dltagram.duckdns.org"));

        content.addView(section("Сброс"));
        addAction(content, "Сбросить настройки DeltaGram",
                "Вернуть удалённые сообщения, пузыри, приватность и числа к значениям по умолчанию",
                null, R.drawable.msg_delete, v -> confirmReset());
    }

    private void confirmReset() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle("Сбросить настройки DeltaGram?");
        builder.setMessage("Будут сброшены только настройки DeltaGram. Аккаунт, чаты и карты не затрагиваются.");
        builder.setPositiveButton("Сбросить", (dialog, which) -> resetSettings());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private void resetSettings() {
        SharedPreferences.Editor editor = prefs().edit();
        for (String key : prefs().getAll().keySet()) {
            if (key.startsWith("deltagram_")) {
                editor.remove(key);
            }
        }
        editor.apply();

        SharedConfig.keepDeletedMessages = true;
        SharedConfig.hidePhoneNumber = false;
        SharedConfig.showIdAndDc = false;
        SharedConfig.disableNumberRounding = false;
        prefs().edit().remove("delta_default_profile_tab").apply();
        prefs().edit().remove("delta_filter_zalgo").apply();
        prefs().edit().remove("delta_time_with_seconds").apply();
        SharedConfig.bubbleRadius = 22;
        SharedConfig.updateChatListSwipeSetting(SwipeGestureSettingsView.SWIPE_GESTURE_ARCHIVE);
        NotificationCenter.getInstance(currentAccount).postNotificationName(
                NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
        finishFragment();
    }

    private void showSwipeActionDialog() {
        final int[] values = {
                SwipeGestureSettingsView.SWIPE_GESTURE_ARCHIVE,
                SwipeGestureSettingsView.SWIPE_GESTURE_PIN,
                SwipeGestureSettingsView.SWIPE_GESTURE_READ,
                SwipeGestureSettingsView.SWIPE_GESTURE_MUTE,
                SwipeGestureSettingsView.SWIPE_GESTURE_DELETE,
                SwipeGestureSettingsView.SWIPE_GESTURE_FOLDERS
        };
        CharSequence[] labels = {
                LocaleController.getString(R.string.SwipeSettingsArchive),
                LocaleController.getString(R.string.SwipeSettingsPin),
                LocaleController.getString(R.string.SwipeSettingsRead),
                LocaleController.getString(R.string.SwipeSettingsMute),
                LocaleController.getString(R.string.SwipeSettingsDelete),
                LocaleController.getString(R.string.SwipeSettingsFolders)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle("Действие свайпа");
        builder.setItems(labels, (dialog, which) -> {
            SharedConfig.updateChatListSwipeSetting(values[which]);
            if (lastValueView != null) {
                lastValueView.setText(swipeActionLabel());
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private String profileTabLabel() {
        switch (prefs().getInt("delta_default_profile_tab", 0)) {
            case 1: return "Медиа";
            case 2: return "Общие группы";
            default: return "Стандартная";
        }
    }

    private void showProfileTabDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle("Вкладка профиля по умолчанию");
        builder.setItems(new CharSequence[]{"Стандартная", "Медиа", "Общие группы"}, (dialog, which) -> {
            prefs().edit().putInt("delta_default_profile_tab", which).apply();
            if (profileTabValueView != null) profileTabValueView.setText(profileTabLabel());
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private String swipeActionLabel() {
        int action = SharedConfig.getChatSwipeAction(currentAccount);
        switch (action) {
            case SwipeGestureSettingsView.SWIPE_GESTURE_PIN:
                return LocaleController.getString(R.string.SwipeSettingsPin);
            case SwipeGestureSettingsView.SWIPE_GESTURE_READ:
                return LocaleController.getString(R.string.SwipeSettingsRead);
            case SwipeGestureSettingsView.SWIPE_GESTURE_MUTE:
                return LocaleController.getString(R.string.SwipeSettingsMute);
            case SwipeGestureSettingsView.SWIPE_GESTURE_DELETE:
                return LocaleController.getString(R.string.SwipeSettingsDelete);
            case SwipeGestureSettingsView.SWIPE_GESTURE_FOLDERS:
                return LocaleController.getString(R.string.SwipeSettingsFolders);
            case SwipeGestureSettingsView.SWIPE_GESTURE_ARCHIVE:
            default:
                return LocaleController.getString(R.string.SwipeSettingsArchive);
        }
    }

    private String currentTextSizeLabel() {
        return SharedConfig.fontSize + " sp";
    }

    private SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    private TextView section(String text) {
        TextView view = new TextView(getParentActivity());
        view.setText(text.toUpperCase());
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTypeface(AndroidUtilities.bold());
        view.setLetterSpacing(0.06f);
        view.setPadding(dp(21), dp(22), dp(21), dp(8));
        return view;
    }

    private View category(String title, String subtitle, int icon, int targetPage) {
        return action(title, subtitle, icon, v -> presentFragment(DeltaGramSettingsActivity.of(targetPage)));
    }

    private View action(String title, String subtitle, int icon, View.OnClickListener listener) {
        return action(title, subtitle, null, icon, listener);
    }

    private View action(String title, String subtitle, String value, int icon, View.OnClickListener listener) {
        Context context = getParentActivity();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setPadding(dp(21), dp(10), dp(21), dp(10));

        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        row.addView(image, LayoutHelper.createLinear(24, 24));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(16), 0, 0, 0);

        TextView name = new TextView(context);
        name.setText(title);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView description = new TextView(context);
        description.setText(subtitle);
        description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        description.setLineSpacing(dp(1), 1f);
        texts.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(value)) {
            TextView valueView = new TextView(context);
            valueView.setText(value);
            valueView.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            valueView.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, dp(8), 0));
            lastValueView = valueView;
        }

        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.attach_arrow_right);
        chevron.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        row.addView(chevron, LayoutHelper.createLinear(18, 18, 0, 0, 0, 0));

        row.setOnClickListener(listener);
        return row;
    }

    private void addAction(LinearLayout parent, String title, String subtitle, String value, int icon, View.OnClickListener listener) {
        parent.addView(action(title, subtitle, value, icon, listener), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void addChoice(LinearLayout parent, String title, String subtitle, String value, int icon, Runnable onClick) {
        parent.addView(action(title, subtitle, value, icon, v -> onClick.run()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void addSwitch(LinearLayout parent, String title, String subtitle, boolean checked, Switch.OnCheckedChangeListener listener) {
        Context context = getParentActivity();
        FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setPadding(dp(21), dp(10), dp(21), dp(10));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(context);
        name.setText(title);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView description = new TextView(context);
        description.setText(subtitle);
        description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        description.setLineSpacing(dp(1), 1f);
        texts.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, 0, dp(64), 0));

        Switch toggle = new Switch(context, getResourceProvider());
        toggle.setChecked(checked, false);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle, LayoutHelper.createFrame(44, 26, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked(), true));
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void addBubbleRadius(LinearLayout parent) {
        Context context = getParentActivity();
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(21), dp(10), dp(21), dp(14));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(context);
        name.setText("Радиус пузырей");
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        header.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView value = new TextView(context);
        value.setText(SharedConfig.bubbleRadius + " dp");
        value.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(value, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        block.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView description = new TextView(context);
        description.setText("Скругление сообщений. 17 — Telegram, 22 — плавные углы DeltaGram.");
        description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        description.setLineSpacing(dp(1), 1f);
        block.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        SeekBar seek = new SeekBar(context);
        seek.setMax(24);
        seek.setProgress(Math.max(0, Math.min(24, SharedConfig.bubbleRadius)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + " dp");
                if (fromUser) {
                    setBubbleRadius(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setBubbleRadius(seekBar.getProgress());
                NotificationCenter.getInstance(currentAccount).postNotificationName(
                        NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
            }
        });
        block.addView(seek, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        parent.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void setBubbleRadius(int radius) {
        SharedConfig.bubbleRadius = Math.max(0, Math.min(24, radius));
        prefs().edit().putInt("deltagram_bubble_radius", SharedConfig.bubbleRadius).apply();
    }

    private static final class BuildVariant {
        static String versionLine() {
            try {
                return "v" + ApplicationLoader.applicationContext.getPackageManager()
                        .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "v?";
            }
        }
    }
}
