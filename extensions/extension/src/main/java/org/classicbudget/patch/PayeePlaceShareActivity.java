package org.classicbudget.patch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Entry point for text from the Android Sharesheet. The private editor asks the user to save. */
public final class PayeePlaceShareActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent incoming = getIntent();
        if (Intent.ACTION_SEND.equals(incoming.getAction()) && "text/plain".equals(incoming.getType())) {
            String text = sharedText(incoming);
            if (MapPoint.acceptsLength(text) && PayeePlaceEditorActivity.hasDraft(this)) {
                startActivity(new Intent(this, PayeePlaceEditorActivity.class)
                    .putExtra(PayeePlaceEditorActivity.SHARE, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } else {
                Toast.makeText(this, PlusLang.tr("Start moving a payee place in YNAB Plus first.",
                    "Najpierw otwórz przenoszenie miejsca płatnika w YNAB Plus."), Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }

    private static String sharedText(Intent incoming) {
        try {
            CharSequence text = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT);
            return text == null || text.length() > 4096 ? null : text.toString();
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
