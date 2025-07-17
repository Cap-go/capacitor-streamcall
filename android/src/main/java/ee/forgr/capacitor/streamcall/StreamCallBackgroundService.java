package ee.forgr.capacitor.streamcall;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;

public class StreamCallBackgroundService extends Service {

    private static final String TAG = "StreamCallBackgroundService";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");
        // Keep the service running even if the app is killed
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
        // The START_STICKY flag in onStartCommand already handles restarts properly
        // Removing manual restart to avoid background execution limits
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
