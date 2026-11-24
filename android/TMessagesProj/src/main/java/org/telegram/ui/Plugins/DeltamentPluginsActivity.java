package org.telegram.ui.Plugins;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

import java.util.List;

/** Installs, toggles and removes DeltaGram .plugin files. */
public class DeltamentPluginsActivity extends BaseFragment {

    private static final int REQUEST_INSTALL = 5107;

    private final int currentAccount = UserConfig.selectedAccount;
    private final DeltamentPluginManager manager = DeltamentPluginManager.getInstance();
    private final DeltamentPluginManager.Listener listener = this::rebuild;

    private LinearLayout content;

    @Override
    public boolean onFragmentCreate() {
        manager.init();
        manager.addListener(listener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        manager.removeListener(listener);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Плагины");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        fragmentView = scroll;

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(32));
        scroll.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rebuild();
        return scroll;
    }

    private void rebuild() {
        if (content == null) {
            return;
        }
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        content.removeAllViews();

        content.addView(hint(context,
                "Плагин — это файл .plugin (zip с manifest.json и classes.dex). "
                        + "Установленные плагины загружаются при старте приложения. "
                        + "Плагины получают полный доступ к приложению, ставь только те, которым доверяешь."));

        content.addView(actionRow(context, "Установить .plugin", "Выбрать файл на устройстве",
                v -> pickFile()));

        final List<DeltamentPluginInfo> plugins = manager.getPlugins();
        content.addView(section(context, plugins.isEmpty()
                ? "ПЛАГИНЫ"
                : "ПЛАГИНЫ · " + plugins.size()));

        if (plugins.isEmpty()) {
            content.addView(hint(context, "Пока ничего не установлено."));
            return;
        }

        for (DeltamentPluginInfo info : plugins) {
            content.addView(pluginRow(context, info));
        }
    }

    private View pluginRow(Context context, DeltamentPluginInfo info) {
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setPadding(dp(21), dp(10), dp(21), dp(10));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        final TextView name = new TextView(context);
        name.setText(info.name);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final StringBuilder subtitle = new StringBuilder();
        subtitle.append(info.python ? "Python · " : "DEX · ");
        subtitle.append("v").append(info.version);
        if (!TextUtils.isEmpty(info.author)) {
            subtitle.append(" · ").append(info.author);
        }
        if (!TextUtils.isEmpty(info.description)) {
            subtitle.append("\n").append(info.description);
        }
        final TextView details = new TextView(context);
        details.setText(subtitle);
        details.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        details.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        details.setLineSpacing(dp(1), 1f);
        texts.addView(details, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        if (info.error != null) {
            final TextView error = new TextView(context);
            error.setText(info.error);
            error.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            error.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            texts.addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }

        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, 0, dp(64), 0));

        final Switch toggle = new Switch(context, getResourceProvider());
        toggle.setChecked(info.enabled, false);
        toggle.setOnCheckedChangeListener((view, checked) -> manager.setPluginEnabled(info, checked));
        row.addView(toggle, LayoutHelper.createFrame(44, 26, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        row.setOnClickListener(v -> presentFragment(
                DeltamentPluginSettingsActivity.of(info.id, info.name)));
        row.setOnLongClickListener(v -> {
            confirmDelete(info);
            return true;
        });
        return row;
    }

    private void confirmDelete(DeltamentPluginInfo info) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Удалить плагин?");
        builder.setMessage(info.name + " будет выключен и удалён с устройства.");
        builder.setPositiveButton("Удалить", (dialog, which) -> manager.delete(info));
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void pickFile() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            activity.startActivityForResult(intent, REQUEST_INSTALL);
        } catch (Throwable e) {
            showError(e.getMessage());
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_INSTALL || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null || getParentActivity() == null) {
            return;
        }
        try {
            final DeltamentPluginInfo info = manager.install(getParentActivity(), data.getData());
            rebuild();
            if (info != null && info.error != null) {
                showError(info.error);
            }
        } catch (Throwable e) {
            showError(e.getMessage());
        }
    }

    private void showError(String message) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Не удалось установить плагин");
        builder.setMessage(TextUtils.isEmpty(message) ? "Неизвестная ошибка" : message);
        builder.setPositiveButton("Ок", null);
        showDialog(builder.create());
    }

    // ------------------------------------------------------------------- rows

    private TextView section(Context context, String text) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLetterSpacing(0.06f);
        view.setPadding(dp(21), dp(22), dp(21), dp(8));
        return view;
    }

    private TextView hint(Context context, String text) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(dp(21), dp(12), dp(21), dp(12));
        return view;
    }

    private View actionRow(Context context, String title, String subtitle, View.OnClickListener onClick) {
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setPadding(dp(21), dp(12), dp(21), dp(12));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        final TextView name = new TextView(context);
        name.setText(title);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView description = new TextView(context);
        description.setText(subtitle);
        description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        texts.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL));
        row.setOnClickListener(onClick);
        return row;
    }
}
