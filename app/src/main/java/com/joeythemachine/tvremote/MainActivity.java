package com.joeythemachine.tvremote;

import android.app.Activity;
import android.os.Bundle;

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
        });
        setContentView(remote);
        tv = new TvClient(this, text -> remote.setStatus(text));
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
