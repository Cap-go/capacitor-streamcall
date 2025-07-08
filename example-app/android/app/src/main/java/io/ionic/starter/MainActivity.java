package io.ionic.starter;

import android.os.Bundle;
import android.content.Intent;
import android.util.Log;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

  private static final String TAG = "MainActivity";


  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Log the initial intent
    Intent intent = getIntent();
    logIntent(intent);
  }

  @Override
  protected void onRestoreInstanceState(Bundle savedInstanceState) {
    super.onRestoreInstanceState(savedInstanceState);
    Log.d(TAG, "onRestoreInstanceState called, restoring bundle:");
    logBundle(savedInstanceState);
  }

  private void logBundle(Bundle bundle) {
    if (bundle == null) {
      Log.d(TAG, "  Bundle is null");
      return;
    }

    for (String key : bundle.keySet()) {
      Object value = bundle.get(key);
      String valueType = value != null ? value.getClass().getSimpleName() : "null";
      String valueStr = value != null ? value.toString() : "null";
      Log.d(TAG, String.format("  %s (%s): %s", key, valueType, valueStr));

      // If this is a nested bundle, log its contents too
      if (value instanceof Bundle) {
        Log.d(TAG, "  Nested bundle for key: " + key);
        logBundle((Bundle) value);
      }
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
      super.onNewIntent(intent);
      logIntent(intent);
      setIntent(intent);
  }

  private void logIntent(Intent intent) {
    if (intent == null) {
      Log.d(TAG, "Received null intent");
      return;
    }

    Log.d(TAG, "Received intent:");
    Log.d(TAG, "  Action: " + intent.getAction());
    Log.d(TAG, "  Data URI: " + intent.getDataString());
    Log.d(TAG, "  Type: " + intent.getType());
    Log.d(TAG, "  Package: " + intent.getPackage());
    Log.d(TAG, "  Component: " + (intent.getComponent() != null ? intent.getComponent().flattenToString() : "null"));

    Bundle extras = intent.getExtras();
    if (extras != null) {
      Log.d(TAG, "  Extras:");
      for (String key : extras.keySet()) {
        Object value = extras.get(key);
        Log.d(TAG, "    " + key + ": " + (value != null ? value.toString() : "null"));
      }
    } else {
      Log.d(TAG, "  No extras");
    }

    String[] categories = intent.getCategories() != null ?
        intent.getCategories().toArray(new String[0]) : new String[0];
    Log.d(TAG, "  Categories: " + String.join(", ", categories));

    int flags = intent.getFlags();
    Log.d(TAG, "  Flags: " + String.format("0x%08X", flags));
  }

  @Override
  public void onResume() {
    super.onResume();
  }
}
