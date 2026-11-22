package org.classicbudget.patch;
import android.app.Activity;import android.os.Bundle;import android.widget.Toast;
public final class ReadOnlyEditActivity extends Activity {
 protected void onCreate(Bundle state){super.onCreate(state);Toast.makeText(this,"TEST tylko do odczytu — edycja transakcji jest wyłączona",Toast.LENGTH_LONG).show();setResult(RESULT_CANCELED);finish();}
}
