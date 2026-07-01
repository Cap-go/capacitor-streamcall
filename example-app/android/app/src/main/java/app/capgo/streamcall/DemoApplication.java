package app.capgo.streamcall;

import android.app.Application;
import android.util.Log;

import ee.forgr.capacitor.streamcall.StreamCallPlugin;

public class DemoApplication extends Application {
    private static final String TAG = "DemoApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Application onCreate called");
        initializeApp();
    }

    private void initializeApp() {
        Log.i(TAG, "Initializing application...");
        com.google.firebase.FirebaseApp.initializeApp(this);
        try {
            StreamCallPlugin.preLoadInit(this, this);
            Log.i(TAG, "StreamVideo Plugin preLoadInit invoked successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to pre-initialize StreamVideo Plugin", e);
        }
        Log.i(TAG, "Application initialization completed");
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "System is running low on memory");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.d(TAG, "onTrimMemory called with level: " + level);
    }
}
