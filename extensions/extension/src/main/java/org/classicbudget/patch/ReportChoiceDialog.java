package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.view.View;
import android.widget.*;

/** Short choices stay discoverable in a shallow window; long content can still scroll. */
final class ReportChoiceDialog {
    interface Selected { void pick(int index); }

    static void show(Activity activity, String title, String[] labels, int checked, Selected selected) {
        float density = activity.getResources().getDisplayMetrics().density;
        int columns = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 2 : 1;
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (12 * density);
        rows.setPadding(padding, 0, padding, 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.setContentDescription("report-choice-list");
        scroll.addView(rows);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(scroll)
            .setNegativeButton(ReportsActivity.tr("Cancel", "Anuluj"), null).create();
        for (int first = 0; first < labels.length; first += columns) {
            LinearLayout line = new LinearLayout(activity);
            for (int column = 0; column < columns; column++) {
                final int index = first + column;
                if (index >= labels.length) {
                    line.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1));
                    continue;
                }
                RadioButton option = new RadioButton(activity);
                option.setText(labels[index]);
                option.setTextSize(14);
                option.setMinHeight((int) (48 * density));
                option.setPadding(padding / 2, padding / 2, padding / 2, padding / 2);
                option.setChecked(index == checked);
                option.setContentDescription("report-choice:" + index);
                option.setOnClickListener(view -> {
                    dialog.dismiss();
                    selected.pick(index);
                });
                line.addView(option, new LinearLayout.LayoutParams(0, -2, 1));
            }
            rows.addView(line);
        }
        dialog.show();
    }
}
