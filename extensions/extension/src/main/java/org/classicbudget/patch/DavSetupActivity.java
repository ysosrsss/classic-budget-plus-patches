package org.classicbudget.patch;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.text.InputType;
import android.widget.*;
import java.io.*;
import org.json.*;

public final class DavSetupActivity extends Activity {
    private EditText email,password;private TextView status;private Button connect;
    protected void onCreate(Bundle state){
        super.onCreate(state);setTitle("Koofr TEST — tylko odczyt");
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);
        int padding=(int)(24*getResources().getDisplayMetrics().density);layout.setPadding(padding,padding,padding,padding);
        TextView intro=new TextView(this);intro.setText("Koofr • YNAB4_PLUS_TEST\n\nTa wersja pobiera kopię budżetu. Nie wysyła zmian ani rejestracji urządzenia do chmury.");layout.addView(intro);
        email=new EditText(this);email.setHint("E-mail konta Koofr");email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);layout.addView(email);
        password=new EditText(this);password.setHint("Hasło aplikacji WebDAV");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);layout.addView(password);
        connect=new Button(this);connect.setText("Połącz i wybierz budżet TEST");layout.addView(connect);
        status=new TextView(this);layout.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        connect.setOnClickListener(new android.view.View.OnClickListener(){public void onClick(android.view.View view){connect();}});
        try {JSONObject saved=DavProvider.config();email.setText(saved.getString("username"));password.setText(saved.getString("app_password"));}catch(Exception ignored){}
    }
    private void connect(){
        final String username=email.getText().toString().trim(), secret=password.getText().toString();
        connect.setEnabled(false);status.setText("Sprawdzam katalog TEST…");
        new Thread(new Runnable(){public void run(){
            String failure=null;
            try {
                String anchor=getSharedPreferences("dav-test-profile",0).getString("username",username);
                if(!anchor.equals(username))throw new IOException("Zmiana konta wymaga nowego stanu aplikacji TEST");
                // Only this explicit user action permits another authentication attempt.
                DavProvider.clearAuthenticationBlock();
                new DavClient(username,secret,null).list("/");
                JSONObject config=new JSONObject();config.put("base_url","https://app.koofr.net/dav/Koofr/");config.put("test_root","YNAB4_PLUS_TEST/__write_budget_lab__");config.put("username",username);config.put("app_password",secret);
                FileOutputStream out=openFileOutput("webdav-test.json",0);try{out.write(config.toString().getBytes("UTF-8"));}finally{out.close();}
            } catch(Throwable error){DavProvider.diagnostic(error);
                if(error instanceof java.net.ConnectException || error instanceof java.net.UnknownHostException || error instanceof java.net.SocketTimeoutException)
                    failure="Nie udało się połączyć z Koofr. Sprawdź połączenie internetowe lub spróbuj innej sieci. To nie jest potwierdzenie błędnego hasła.";
                else failure=error instanceof IOException?error.getMessage():"Nie udało się skonfigurować odczytu TEST";}
            final String message=failure;runOnUiThread(new Runnable(){public void run(){
                connect.setEnabled(true);if(message==null){password.setText("");setResult(RESULT_OK);finish();}
                else status.setText(message);
            }});
        }}).start();
    }
}
