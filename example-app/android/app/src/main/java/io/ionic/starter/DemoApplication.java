package io.ionic.starter;

import android.app.Application;
import android.util.Log;

import com.facebook.flipper.plugins.inspector.InspectorFlipperPlugin;

import com.facebook.flipper.android.AndroidFlipperClient;
import com.facebook.flipper.core.FlipperClient;
import com.facebook.flipper.plugins.inspector.DescriptorMapping;
import com.facebook.flipper.plugins.network.NetworkFlipperPlugin;
import com.facebook.flipper.plugins.sharedpreferences.SharedPreferencesFlipperPlugin;
import com.facebook.soloader.SoLoader;

import ee.forgr.capacitor.streamcall.ApiKeyManager;
import ee.forgr.capacitor.streamcall.StreamCallManager;

public class DemoApplication extends Application {
    private static final String TAG = "DemoApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Application onCreate called");
        initializeApp();

        SoLoader.init(this, false);

        FlipperClient client = AndroidFlipperClient.getInstance(this);
        client.addPlugin(new InspectorFlipperPlugin(this, DescriptorMapping.withDefaults()));
        client.addPlugin(new SharedPreferencesFlipperPlugin(this));
        client.addPlugin(new NetworkFlipperPlugin());
        client.start();
    }

    private void initializeApp() {
        Log.i(TAG, "Initializing application...");
        // Initialize Firebase
        com.google.firebase.FirebaseApp.initializeApp(this);
         try {
            StreamCallManager.INSTANCE.initialize(this);
            Log.i(TAG, "StreamCallManager initialized successfully");
         } catch (Exception e) {
             Log.e(TAG, "Failed to initialize StreamCallManager", e);
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
