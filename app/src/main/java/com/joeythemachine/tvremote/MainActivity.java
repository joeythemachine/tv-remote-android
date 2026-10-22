package com.joeythemachine.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;

public class MainActivity extends Activity {

    private TvClient tv;
    private RemoteView remote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        remote = new RemoteView(this, new RemoteView.Actions() {
            @Override public void onKey(String key) { tv.sendKey(key); }
            @Override public void onPower() { tv.power(); }
            @Override public void onExit() { finish(); }
            @Override public void onStatusHold() { showAddressDialog(); }
        });
        setContentView(remote);
        tv = new TvClient(this, text -> remote.setStatus(text));
    }

    private void showAddressDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(tv.getTvIp());
        input.setSelectAllOnFocus(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle("TV address")
                .setMessage("This tablet: " + tv.getTabletIp()
                        + "\n\nThe TV's address is on the TV under Network \u2192 Network Status \u2192 IP Settings.")
                .setView(input)
                .setPositiveButton("Connect", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) tv.setTvIp(ip);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tv.start();
    }

    @Override
    protected void onPause() {
        tv.stop();   // drop the connection while the app isn't on screen
        super.onPause();
    }
}
