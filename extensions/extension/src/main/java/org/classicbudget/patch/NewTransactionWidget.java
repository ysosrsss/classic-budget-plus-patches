package org.classicbudget.patch;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.RemoteViews;

/** Launches the normal app flow and uses the original Add Transaction listener once the budget is ready. */
public class NewTransactionWidget extends AppWidgetProvider {
    private static final String REQUEST = "org.classicbudget.patch.widget.NEW_TRANSACTION";
    private static final String START = "com.youneedabudget.ynab.app.StartActivity";

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, getClass()));
            if (ids.length > 0) onUpdate(context, manager, ids);
        }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        String app = context.getPackageName();
        String layoutName = compact() ? "cb_new_transaction_compact" : "cb_new_transaction_widget";
        int layout = context.getResources().getIdentifier(layoutName, "layout", app);
        int button = context.getResources().getIdentifier("cb_new_transaction_button", "id", app);
        // A task created by an earlier version can still have OpenActivity as its recorded base.
        Intent open = new Intent(context, LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(context, 0, open, flags);
        RemoteViews views = new RemoteViews(app, layout);
        String title = PlusLang.tr("New transaction", "Nowa transakcja");
        views.setTextViewText(button, compact() ? "+" : title);
        views.setContentDescription(button, title);
        views.setOnClickPendingIntent(button, tap);
        for (int id : ids) manager.updateAppWidget(id, views);
    }

    protected boolean compact() {
        return false;
    }

    public static final class Compact extends NewTransactionWidget {
        @Override protected boolean compact() {
            return true;
        }
    }

    /** Old widget PendingIntents can still reference this component during an update. */
    public static class OpenActivity extends Activity {
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            PlusDiagnostics.record(this, PlusDiagnostics.Event.WIDGET_LAUNCH);
            // A launcher MAIN intent can simply bring an existing task to the foreground.
            // Route this explicit request through the original budget and passcode startup.
            Intent request = new Intent(REQUEST).setClassName(getPackageName(), START);
            request.putExtra(REQUEST, true);
            startActivity(request);
            finish();
        }
    }

    /** Hands its task identity to the native app even when the widget starts a new task. */
    public static final class LaunchActivity extends OpenActivity { }

    /** Original navigation reconstructs the Intent at each step, so forward only our request. */
    public static void forward(Activity source, Intent destination) {
        Intent request = source.getIntent();
        if (request != null && request.getBooleanExtra(REQUEST, false)) destination.putExtra(REQUEST, true);
    }

    /** Called after the category screen attaches its normal Add Transaction listener. */
    public static void ready(Activity activity, View addButton) {
        Intent request = activity.getIntent();
        if (request == null || !request.getBooleanExtra(REQUEST, false)) return;
        PlusDiagnostics.record(activity, PlusDiagnostics.Event.WIDGET_FORM_READY);
        ViewTreeObserver.OnWindowFocusChangeListener focus = new ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override public void onWindowFocusChanged(boolean focused) {
                if (!focused || activity.isFinishing()) return;
                ViewTreeObserver observer = addButton.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(this);
                if (request.getBooleanExtra(REQUEST, false)) {
                    // The original passcode flow can finish and reopen this Activity using the same Intent.
                    request.removeExtra(REQUEST);
                    PlusDiagnostics.record(activity, PlusDiagnostics.Event.WIDGET_ADD_REQUESTED);
                    addButton.performClick();
                }
            }
        };
        addButton.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
        addButton.post(() -> focus.onWindowFocusChanged(addButton.hasWindowFocus()));
    }
}
