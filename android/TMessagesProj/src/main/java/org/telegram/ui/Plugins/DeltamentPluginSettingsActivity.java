package org.telegram.ui.Plugins;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

/** Renders the settings a Python plugin returns from {@code create_settings()}. */
public class DeltamentPluginSettingsActivity extends BaseFragment {

    private static final String ARG_ID = "plugin_id";
    private static final String ARG_NAME = "plugin_name";

    private String pluginId;
    private String pluginName;
    private LinearLayout content;

    public static DeltamentPluginSettingsActivity of(String id, String name) {
        final Bundle args = new Bundle();
        args.putString(ARG_ID, id);
        args.putString(ARG_NAME, name);
        return new DeltamentPluginSettingsActivity(args);
    }

    public DeltamentPluginSettingsActivity() {
        super();
    }

    public DeltamentPluginSettingsActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        final Bundle args = getArguments();
        if (args != null) {
            pluginId = args.getString(ARG_ID, "");
            pluginName = args.getString(ARG_NAME, "");
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(TextUtils.isEmpty(pluginName) ? "Плагин" : pluginName);
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
        final Context context = getParentActivity();
        if (content == null || context == null) {
            return;
        }
        content.removeAllViews();

        final String json = DeltamentPluginManager.pythonSettings(pluginId);
        if (TextUtils.isEmpty(json)) {
            content.addView(hint(context, "Плагин не загружен. Включи его в списке плагинов."));
            return;
        }
        try {
            if (json.trim().startsWith("{")) {
                final JSONObject error = new JSONObject(json);
                content.addView(hint(context, error.optString("error", "Не удалось открыть настройки")));
                return;
            }
            final JSONArray items = new JSONArray(json);
            if (items.length() == 0) {
                content.addView(hint(context, "У этого плагина нет настроек."));
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final View row = rowFor(context, item, i);
                if (row != null) {
                    content.addView(row);
                }
            }
        } catch (Throwable e) {
            content.addView(hint(context, e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private View rowFor(Context context, JSONObject item, int index) {
        final String type = item.optString("type", "");
        if ("Header".equals(type)) {
            return section(context, item.optString("text", ""));
        }
        if ("Divider".equals(type)) {
            final View divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            final LinearLayout wrapper = new LinearLayout(context);
            wrapper.setPadding(dp(21), dp(6), dp(21), dp(6));
            wrapper.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
            return wrapper;
        }
        if ("Switch".equals(type)) {
            return switchRow(context, item);
        }
        if ("Selector".equals(type)) {
            return selectorRow(context, item);
        }
        if ("Text".equals(type)) {
            return actionRow(context, item.optString("text", ""), item.optBoolean("accent", false),
                    v -> runAction(index));
        }
        return null;
    }

    private View switchRow(Context context, JSONObject item) {
        final String key = item.optString("key", "");
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setPadding(dp(21), dp(10), dp(21), dp(10));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        final TextView name = new TextView(context);
        name.setText(item.optString("text", key));
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final String subtext = item.optString("subtext", "");
        if (!TextUtils.isEmpty(subtext)) {
            final TextView description = new TextView(context);
            description.setText(subtext);
            description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            texts.addView(description,
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        }

        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, 0, dp(64), 0));

        final Switch toggle = new Switch(context, getResourceProvider());
        final boolean checked = item.optBoolean("value", item.optBoolean("default", false));
        toggle.setChecked(checked, false);
        toggle.setOnCheckedChangeListener((view, isChecked) ->
                setValue(key, isChecked ? "true" : "false"));
        row.addView(toggle, LayoutHelper.createFrame(44, 26, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked(), true));
        return row;
    }

    private View selectorRow(Context context, JSONObject item) {
        final String key = item.optString("key", "");
        final JSONArray values = item.optJSONArray("items");
        final CharSequence[] labels = new CharSequence[values == null ? 0 : values.length()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = values.optString(i, "");
        }
        int selected = item.optInt("value", item.optInt("default", 0));
        if (selected < 0 || selected >= labels.length) {
            selected = 0;
        }
        final String current = labels.length == 0 ? ""
                : String.valueOf(labels[Math.min(selected, labels.length - 1)]);

        return actionRow(context, item.optString("text", key), false, current,
                v -> {
                    if (getParentActivity() == null || labels.length == 0) {
                        return;
                    }
                    final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setItems(labels, (dialog, which) -> setValue(key, String.valueOf(which)));
                    showDialog(builder.create());
                });
    }

    private void setValue(String key, String jsonValue) {
        final String error = DeltamentPluginManager.pythonSetSetting(pluginId, key, jsonValue);
        if (!TextUtils.isEmpty(error)) {
            showError(error);
        } else {
            rebuild();
        }
    }

    private void runAction(int index) {
        final String error = DeltamentPluginManager.pythonAction(pluginId, index);
        if (!TextUtils.isEmpty(error)) {
            showError(error);
        } else {
            rebuild();
        }
    }

    private void showError(String message) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Ошибка плагина");
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
        view.setTypeface(AndroidUtilities.bold());
        view.setLetterSpacing(0.06f);
        view.setPadding(dp(21), dp(18), dp(21), dp(6));
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

    private View actionRow(Context context, String title, boolean accent, View.OnClickListener onClick) {
        return actionRow(context, title, accent, "", onClick);
    }

    private View actionRow(Context context, String title, boolean accent, String value,
                           View.OnClickListener onClick) {
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setPadding(dp(21), dp(12), dp(21), dp(12));

        final TextView name = new TextView(context);
        name.setText(title);
        name.setTextColor(Theme.getColor(accent
                ? Theme.key_windowBackgroundWhiteBlueText
                : Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        row.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, 0, dp(120), 0));

        if (!TextUtils.isEmpty(value)) {
            final TextView current = new TextView(context);
            current.setText(value);
            current.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            current.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            current.setSingleLine(true);
            current.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(current, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        }

        row.setOnClickListener(onClick);
        return row;
    }
}
