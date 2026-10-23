package com.joeythemachine.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
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
            @Override public void onPlex() { tv.launchPlex(); }
            @Override public void onKeyboard() { showKeyboardDialog(); }
        });
        setContentView(remote);
        tv = new TvClient(this, text -> remote.setStatus(text));
    }

    private void showKeyboardDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setHint("Type here, then tap Send");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Type on the TV")
                .setMessage("Open the search box on the TV first, then type here.")
                .setView(input)
                .setPositiveButton("Send", (d, w) -> {
                    String text = input.getText().toString();
                    if (!text.isEmpty()) tv.sendText(text);
                })
                .setNegativeButton("Cancel", null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                String text = input.getText().toString();
                if (!text.isEmpty()) tv.sendText(text);
                dialog.dismiss();
                return true;
            }
            return false;
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
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
                .setMessage("Version " + appVersion() + "\nThis tablet: " + tv.getTabletIp()
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
