package org.classicbudget.patch;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/** The same Dropbox choices at first sign-in and when changing an existing profile. */
final class DropboxFolderChoice extends LinearLayout {
    private static final String CUSTOM = "cb-dropbox-custom";
    private static final String PATH = "cb-dropbox-path";
    private final RadioGroup choices;
    private final RadioButton usual;
    private final RadioButton custom;
    private final EditText path;
    private final TextView pathHelp;

    DropboxFolderChoice(Context context, String current, boolean editable, String pathDescription, Bundle saved) {
        super(context);
        setOrientation(VERTICAL);
        choices = new RadioGroup(context);
        choices.setOrientation(VERTICAL);
        usual = option("dropbox-folder-default", tr("Default location", "Domyślne położenie"),
            tr("Same as in the original YNAB 4 app.", "Tak jak w oryginalnej aplikacji YNAB 4."));
        custom = option("dropbox-folder-custom", tr("A different Dropbox folder", "Inny folder Dropbox"),
            tr("Type the Dropbox path for a test copy or a separately stored budget.",
                "Wpisz ścieżkę Dropboxa do kopii testowej lub budżetu w osobnym folderze."));
        addView(choices);

        path = new EditText(context);
        path.setSingleLine(true);
        path.setContentDescription(pathDescription);
        path.setHint(tr("Dropbox path", "Ścieżka w Dropboxie"));
        path.setEnabled(editable);
        addView(path);
        pathHelp = new TextView(context);
        pathHelp.setText(tr("Start the path with /, e.g. /YNAB4_PLUS_TEST.",
            "Zacznij ścieżkę od /, np. /YNAB4_PLUS_TEST."));
        addView(pathHelp);

        boolean useCustom = current != null && !current.equals("/");
        String initial = useCustom ? current : "";
        if (editable && saved != null && saved.containsKey(CUSTOM)) {
            useCustom = saved.getBoolean(CUSTOM);
            initial = saved.getString(PATH, "");
        }
        path.setText(initial);
        usual.setEnabled(editable);
        custom.setEnabled(editable);
        choices.check(useCustom ? custom.getId() : usual.getId());
        updatePath();
        choices.setOnCheckedChangeListener((group, id) -> updatePath());
    }

    private RadioButton option(String description, String label, String detail) {
        RadioButton button = new RadioButton(getContext());
        button.setId(View.generateViewId());
        button.setContentDescription(description);
        button.setTextSize(16);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        button.setPadding(button.getPaddingLeft(), pad, button.getPaddingRight(), pad);
        SpannableString text = new SpannableString(label + "\n" + detail);
        text.setSpan(new RelativeSizeSpan(.85f), label.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(text);
        choices.addView(button, new RadioGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void updatePath() {
        boolean useCustom = choices.getCheckedRadioButtonId() == custom.getId();
        if (!useCustom && path.hasFocus()) {
            InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(path.getWindowToken(), 0);
            path.clearFocus();
        }
        path.setVisibility(useCustom ? VISIBLE : GONE);
        pathHelp.setVisibility(useCustom ? VISIBLE : GONE);
    }

    String selectedFolder() {
        if (choices.getCheckedRadioButtonId() == usual.getId()) return "/";
        String folder = DropboxProfile.normalize(path.getText().toString());
        return "/".equals(folder) ? null : folder;
    }

    void showError(String message) {
        if (path.getVisibility() == VISIBLE && path.isEnabled()) {
            path.setError(message);
            return;
        }
        android.widget.Toast.makeText(getContext(), message, android.widget.Toast.LENGTH_LONG).show();
    }

    void save(Bundle state) {
        state.putBoolean(CUSTOM, choices.getCheckedRadioButtonId() == custom.getId());
        state.putString(PATH, path.getText().toString());
    }

    static String defaultLabel() {
        return tr("Default location", "Domyślne położenie");
    }

    private static String tr(String en, String pl) {
        return PlusLang.tr(en, pl);
    }
}
