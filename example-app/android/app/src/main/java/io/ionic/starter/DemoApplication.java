package io.ionic.starter;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.facebook.flipper.plugins.inspector.InspectorFlipperPlugin;

import com.facebook.flipper.android.AndroidFlipperClient;
import com.facebook.flipper.core.FlipperClient;
import com.facebook.flipper.plugins.inspector.DescriptorMapping;
import com.facebook.flipper.plugins.network.NetworkFlipperPlugin;
import com.facebook.flipper.plugins.sharedpreferences.SharedPreferencesFlipperPlugin;
import com.facebook.soloader.SoLoader;

import ee.forgr.capacitor.streamcall.StreamCallPlugin;

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
            StreamCallPlugin.preLoadInit(this);
            Log.i(TAG, "StreamCallPlugin preLoadInit invoked successfully (using StreamCallManager)");
         } catch (Exception e) {
             Log.e(TAG, "Failed to pre-initialize StreamCallPlugin", e);
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
