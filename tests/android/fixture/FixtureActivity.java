package com.simon.doubaotest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.TextView;
/** Controlled Android integration peer, never a substitute for genuine Doubao validation. */
public final class FixtureActivity extends Activity {
    private static FixtureActivity visible;
    private static long generation;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); visible=this;
        TextView label=new TextView(this); label.setText("ANDROID INTEGRATION FIXTURE\nNo real voice/video call"); setContentView(label);
        android.content.SharedPreferences store=getSharedPreferences("fixture",0);
        int launches=store.getInt("launches",0)+1;
        store.edit().putInt("launches",launches).commit();
        android.util.Log.i("DoubaoIntegrationFixture","entry="+launches+" uri="+getIntent().getData());
        final long expected=++generation;
        new Handler(getMainLooper()).postDelayed(new Runnable(){public void run(){if(expected==generation)publish();}},store.getInt("delay",0));
    }
    private void publish() {
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("fixture","Integration call control",2));
        Intent end=new Intent(this,FixtureReceiver.class).setAction("fixture.END");
        PendingIntent action=PendingIntent.getBroadcast(this,101,end,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"fixture").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Integration fixture call").setOngoing(true).setCategory(Notification.CATEGORY_CALL)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"挂断",action).build()).build();
        manager.notify(101,notification);
    }
    static void end(Context context) {
        generation++;
        ((NotificationManager)context.getSystemService(NOTIFICATION_SERVICE)).cancel(101);
        if(visible!=null)visible.finish();
    }
    @Override public void onDestroy(){if(visible==this)visible=null;super.onDestroy();}
}
