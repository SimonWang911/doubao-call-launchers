package com.simon.doubaotest;
import android.content.*;
public final class FixtureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if("fixture.END".equals(intent.getAction()))FixtureActivity.end(context);
        else if("fixture.CONFIGURE".equals(intent.getAction()))context.getSharedPreferences("fixture",0).edit().putInt("delay",intent.getIntExtra("delay",0)).commit();
    }
}
