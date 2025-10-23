# @capgo/capacitor-stream-call
 <a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/Cap-go/capgo/main/assets/capgo_banner.png' alt='Capgo - Instant updates for capacitor'/></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin"> ➡️ Get Instant updates for your App with Capgo</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin"> Missing a feature? We’ll build the plugin for you 💪</a></h2>
</div>

A Capacitor plugin that uses the [Stream Video SDK](https://getstream.io/) to enable video calling functionality in your app.

## Installation

```bash
npm install @capgo/capacitor-stream-call
npx cap sync
```

## Configuration

### iOS Setup

#### 1. API Key Configuration
Add your Stream Video API key to `ios/App/App/Info.plist`:

```xml
<dict>
  <key>CAPACITOR_STREAM_VIDEO_APIKEY</key>
  <string>your_api_key_here</string>
  <!-- other keys -->
</dict>
```

#### 2. Localization (Optional)
To support multiple languages:

1. Add localization files to your Xcode project:
   - `/App/App/en.lproj/Localizable.strings`
   - `/App/App/en.lproj/Localizable.stringsdict`

2. Add translations in `Localizable.strings`:
```swift
"stream.video.call.incoming" = "Incoming call from %@";
"stream.video.call.accept" = "Accept";
"stream.video.call.reject" = "Reject";
"stream.video.call.hangup" = "Hang up";
"stream.video.call.joining" = "Joining...";
"stream.video.call.reconnecting" = "Reconnecting...";
```

3. Configure localization in your `AppDelegate.swift`:
```swift
import StreamVideo

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        Appearance.localizationProvider = { key, table in
            Bundle.main.localizedString(forKey: key, value: nil, table: table)
        }
        return true
    }
}
```

### Android Setup

#### 1. API Key Configuration
Add your Stream Video API key to `android/app/src/main/res/values/strings.xml`:

```xml
<string name="CAPACITOR_STREAM_VIDEO_APIKEY">your_api_key_here</string>
```

#### 2. MainActivity Configuration
Modify your `MainActivity.java` to handle incoming calls:

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
  super.onCreate(savedInstanceState);
  
  // Enable activity to show over lock screen
  if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
    setShowWhenLocked(true);
    setTurnScreenOn(true);
  } else {
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
  }
}
```

#### 3. Application Class Configuration
Create or modify your Application class to initialize the plugin:

```java
import ee.forgr.capacitor.streamcall.StreamCallPlugin;

@Override
public void onCreate() {
  super.onCreate();
  
  // Initialize Firebase
  com.google.firebase.FirebaseApp.initializeApp(this);
  
  // Pre-initialize StreamCall plugin
  try {
    StreamCallPlugin.preLoadInit(this, this);
  } catch (Exception e) {
    Log.e("App", "Failed to pre-initialize StreamVideo Plugin", e);
  }
}
```

> **Note:** If you don't have an Application class, you need to create one and reference it in your `AndroidManifest.xml` with `android:name=".YourApplicationClass"`.

#### 4. Localization (Optional)
Add string resources for different languages:

**Default (`values/strings.xml`):**
```xml
<resources>
    <string name="stream_video_call_incoming">Incoming call from %1$s</string>
    <string name="stream_video_call_accept">Accept</string>
    <string name="stream_video_call_reject">Reject</string>
    <string name="stream_video_call_hangup">Hang up</string>
    <string name="stream_video_call_joining">Joining...</string>
    <string name="stream_video_call_reconnecting">Reconnecting...</string>
</resources>
```

**French (`values-fr/strings.xml`):**
```xml
<resources>
    <string name="stream_video_call_incoming">Appel entrant de %1$s</string>
    <string name="stream_video_call_accept">Accepter</string>
    <string name="stream_video_call_reject">Refuser</string>
    <string name="stream_video_call_hangup">Raccrocher</string>
    <string name="stream_video_call_joining">Connexion...</string>
    <string name="stream_video_call_reconnecting">Reconnexion...</string>
</resources>
```

## Displaying Caller Information

When receiving incoming calls, you can access caller information including name, user ID, and profile image. This information is automatically extracted from the call data and passed through the event system.

### Getting Caller Information

The caller information is available in two ways:

**1. Through Call Events**

The `callEvent` listener provides caller information for incoming calls:

```typescript
StreamCall.addListener('callEvent', (event) => {
  if (event.state === 'ringing' && event.caller) {
    console.log('Incoming call from:', event.caller.name || event.caller.userId);
    console.log('Caller image:', event.caller.imageURL);
    // Update your UI to show caller information
    showIncomingCallUI(event.caller);
  }
});
```

**2. Through Incoming Call Events (Android lock-screen)**

The `incomingCall` listener also includes caller information:

```typescript
StreamCall.addListener('incomingCall', (payload) => {
  if (payload.caller) {
    console.log('Lock-screen call from:', payload.caller.name || payload.caller.userId);
    // Update your lock-screen UI
    updateLockScreenUI(payload.caller);
  }
});
```

### Caller Information Structure

```typescript
interface CallMember {
  userId: string;      // User ID (always present)
  name?: string;       // Display name (optional)
  imageURL?: string;   // Profile image URL (optional)
  role?: string;       // User role (optional)
}
```

### Example Implementation

Here's how to implement a proper incoming call screen with caller information:

```typescript
export class CallService {
  private callerInfo: CallMember | null = null;

  constructor() {
    this.setupCallListeners();
  }

  private setupCallListeners() {
    StreamCall.addListener('callEvent', (event) => {
      if (event.state === 'ringing') {
        this.callerInfo = event.caller || null;
        this.showIncomingCallScreen();
      } else if (event.state === 'joined' || event.state === 'left') {
        this.callerInfo = null;
        this.hideIncomingCallScreen();
      }
    });

    // Android lock-screen support
    if (Capacitor.getPlatform() === 'android') {
      StreamCall.addListener('incomingCall', (payload) => {
        this.callerInfo = payload.caller || null;
        this.showLockScreenIncomingCall();
      });
    }
  }

  private showIncomingCallScreen() {
    const callerName = this.callerInfo?.name || 'Unknown Caller';
    const callerImage = this.callerInfo?.imageURL || 'default-avatar.png';
    
    // Update your UI components
    document.getElementById('caller-name').textContent = callerName;
    document.getElementById('caller-image').src = callerImage;
    document.getElementById('incoming-call-screen').style.display = 'block';
  }
}
```

## API

<docgen-index>

* [`login(...)`](#login)
* [`logout()`](#logout)
* [`call(...)`](#call)
* [`endCall()`](#endcall)
* [`joinCall(...)`](#joincall)
* [`setMicrophoneEnabled(...)`](#setmicrophoneenabled)
* [`setCameraEnabled(...)`](#setcameraenabled)
* [`addListener('callEvent', ...)`](#addlistenercallevent-)
* [`addListener('incomingCall', ...)`](#addlistenerincomingcall-)
* [`removeAllListeners()`](#removealllisteners)
* [`enableBluetooth()`](#enablebluetooth)
* [`acceptCall()`](#acceptcall)
* [`rejectCall()`](#rejectcall)
* [`isCameraEnabled()`](#iscameraenabled)
* [`getCallStatus()`](#getcallstatus)
* [`getRingingCall()`](#getringingcall)
* [`toggleViews()`](#toggleviews)
* [`setSpeaker(...)`](#setspeaker)
* [`switchCamera(...)`](#switchcamera)
* [`getCallInfo(...)`](#getcallinfo)
* [`setDynamicStreamVideoApikey(...)`](#setdynamicstreamvideoapikey)
* [`getDynamicStreamVideoApikey()`](#getdynamicstreamvideoapikey)
* [`getCurrentUser()`](#getcurrentuser)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)
* [Enums](#enums)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### login(...)

```typescript
login(options: LoginOptions) => Promise<SuccessResponse>
```

Login to Stream Video service

| Param         | Type                                                  | Description           |
| ------------- | ----------------------------------------------------- | --------------------- |
| **`options`** | <code><a href="#loginoptions">LoginOptions</a></code> | - Login configuration |

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### logout()

```typescript
logout() => Promise<SuccessResponse>
```

Logout from Stream Video service

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### call(...)

```typescript
call(options: CallOptions) => Promise<SuccessResponse>
```

Initiate a call to another user

| Param         | Type                                                | Description          |
| ------------- | --------------------------------------------------- | -------------------- |
| **`options`** | <code><a href="#calloptions">CallOptions</a></code> | - Call configuration |

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### endCall()

```typescript
endCall() => Promise<SuccessResponse>
```

End the current call

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### joinCall(...)

```typescript
joinCall(options: { callId: string; callType: string; }) => Promise<SuccessResponse>
```

Join an existing call

| Param         | Type                                               | Description        |
| ------------- | -------------------------------------------------- | ------------------ |
| **`options`** | <code>{ callId: string; callType: string; }</code> | - Microphone state |

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### setMicrophoneEnabled(...)

```typescript
setMicrophoneEnabled(options: { enabled: boolean; }) => Promise<SuccessResponse>
```

Enable or disable microphone

| Param         | Type                               | Description        |
| ------------- | ---------------------------------- | ------------------ |
| **`options`** | <code>{ enabled: boolean; }</code> | - Microphone state |

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### setCameraEnabled(...)

```typescript
setCameraEnabled(options: { enabled: boolean; }) => Promise<SuccessResponse>
```

Enable or disable camera

| Param         | Type                               | Description    |
| ------------- | ---------------------------------- | -------------- |
| **`options`** | <code>{ enabled: boolean; }</code> | - Camera state |

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### addListener('callEvent', ...)

```typescript
addListener(eventName: 'callEvent', listenerFunc: (event: CallEvent) => void) => Promise<{ remove: () => Promise<void>; }>
```

Add listener for call events

| Param              | Type                                                                | Description                       |
| ------------------ | ------------------------------------------------------------------- | --------------------------------- |
| **`eventName`**    | <code>'callEvent'</code>                                            | - Name of the event to listen for |
| **`listenerFunc`** | <code>(event: <a href="#callevent">CallEvent</a>) =&gt; void</code> | - Callback function               |

**Returns:** <code>Promise&lt;{ remove: () =&gt; Promise&lt;void&gt;; }&gt;</code>

--------------------


### addListener('incomingCall', ...)

```typescript
addListener(eventName: 'incomingCall', listenerFunc: (event: IncomingCallPayload) => void) => Promise<{ remove: () => Promise<void>; }>
```

Listen for lock-screen incoming call (Android only).
Fired when the app is shown by full-screen intent before user interaction.

| Param              | Type                                                                                    |
| ------------------ | --------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'incomingCall'</code>                                                             |
| **`listenerFunc`** | <code>(event: <a href="#incomingcallpayload">IncomingCallPayload</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;{ remove: () =&gt; Promise&lt;void&gt;; }&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

Remove all event listeners

--------------------


### enableBluetooth()

```typescript
enableBluetooth() => Promise<SuccessResponse>
```

Enable bluetooth audio

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### acceptCall()

```typescript
acceptCall() => Promise<SuccessResponse>
```

Accept an incoming call

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### rejectCall()

```typescript
rejectCall() => Promise<SuccessResponse>
```

Reject an incoming call

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### isCameraEnabled()

```typescript
isCameraEnabled() => Promise<CameraEnabledResponse>
```

Check if camera is enabled

**Returns:** <code>Promise&lt;<a href="#cameraenabledresponse">CameraEnabledResponse</a>&gt;</code>

--------------------


### getCallStatus()

```typescript
getCallStatus() => Promise<CallEvent>
```

Get the current call status

**Returns:** <code>Promise&lt;<a href="#callevent">CallEvent</a>&gt;</code>

--------------------


### getRingingCall()

```typescript
getRingingCall() => Promise<CallEvent>
```

Get the current ringing call

**Returns:** <code>Promise&lt;<a href="#callevent">CallEvent</a>&gt;</code>

--------------------


### toggleViews()

```typescript
toggleViews() => Promise<{ newLayout: StreamCallLayout; }>
```

Cycle through the available video layouts

**Returns:** <code>Promise&lt;{ newLayout: <a href="#streamcalllayout">StreamCallLayout</a>; }&gt;</code>

--------------------


### setSpeaker(...)

```typescript
setSpeaker(options: { name: string; }) => Promise<SuccessResponse>
```

Set speakerphone on

| Param         | Type                           | Description         |
| ------------- | ------------------------------ | ------------------- |
| **`options`** | <code>{ name: string; }</code> | - Speakerphone name |

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### switchCamera(...)

```typescript
switchCamera(options: { camera: 'front' | 'back'; }) => Promise<SuccessResponse>
```

Switch camera

| Param         | Type                                        | Description           |
| ------------- | ------------------------------------------- | --------------------- |
| **`options`** | <code>{ camera: 'front' \| 'back'; }</code> | - Camera to switch to |

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### getCallInfo(...)

```typescript
getCallInfo(options: { callId: string; }) => Promise<CallEvent>
```

Get detailed information about an active call including caller details

| Param         | Type                             | Description                      |
| ------------- | -------------------------------- | -------------------------------- |
| **`options`** | <code>{ callId: string; }</code> | - Options containing the call ID |

**Returns:** <code>Promise&lt;<a href="#callevent">CallEvent</a>&gt;</code>

--------------------


### setDynamicStreamVideoApikey(...)

```typescript
setDynamicStreamVideoApikey(options: { apiKey: string; }) => Promise<SuccessResponse>
```

Set a dynamic Stream Video API key that overrides the static one

| Param         | Type                             | Description          |
| ------------- | -------------------------------- | -------------------- |
| **`options`** | <code>{ apiKey: string; }</code> | - The API key to set |

**Returns:** <code>Promise&lt;<a href="#successresponse">SuccessResponse</a>&gt;</code>

--------------------


### getDynamicStreamVideoApikey()

```typescript
getDynamicStreamVideoApikey() => Promise<DynamicApiKeyResponse>
```

Get the currently set dynamic Stream Video API key

**Returns:** <code>Promise&lt;<a href="#dynamicapikeyresponse">DynamicApiKeyResponse</a>&gt;</code>

--------------------


### getCurrentUser()

```typescript
getCurrentUser() => Promise<CurrentUserResponse>
```

Get the current user's information

**Returns:** <code>Promise&lt;<a href="#currentuserresponse">CurrentUserResponse</a>&gt;</code>

--------------------


### Interfaces


#### SuccessResponse

| Prop          | Type                 | Description                          |
| ------------- | -------------------- | ------------------------------------ |
| **`success`** | <code>boolean</code> | Whether the operation was successful |
| **`callId`**  | <code>string</code>  |                                      |


#### LoginOptions

| Prop                          | Type                                                                        | Description                                             |
| ----------------------------- | --------------------------------------------------------------------------- | ------------------------------------------------------- |
| **`token`**                   | <code>string</code>                                                         | Stream Video API token                                  |
| **`userId`**                  | <code>string</code>                                                         | User ID for the current user                            |
| **`name`**                    | <code>string</code>                                                         | Display name for the current user                       |
| **`imageURL`**                | <code>string</code>                                                         | Optional avatar URL for the current user                |
| **`apiKey`**                  | <code>string</code>                                                         | Stream Video API key                                    |
| **`magicDivId`**              | <code>string</code>                                                         | ID of the HTML element where the video will be rendered |
| **`pushNotificationsConfig`** | <code><a href="#pushnotificationsconfig">PushNotificationsConfig</a></code> |                                                         |


#### PushNotificationsConfig

| Prop                   | Type                |
| ---------------------- | ------------------- |
| **`pushProviderName`** | <code>string</code> |
| **`voipProviderName`** | <code>string</code> |


#### CallOptions

| Prop          | Type                                                                                                                                                                                                                      | Description                                                     |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| **`userIds`** | <code>string[]</code>                                                                                                                                                                                                     | User ID of the person to call                                   |
| **`type`**    | <code><a href="#calltype">CallType</a></code>                                                                                                                                                                             | Type of call, defaults to 'default'                             |
| **`ring`**    | <code>boolean</code>                                                                                                                                                                                                      | Whether to ring the other user, defaults to true                |
| **`team`**    | <code>string</code>                                                                                                                                                                                                       | Team name to call                                               |
| **`video`**   | <code>boolean</code>                                                                                                                                                                                                      | Whether to start the call with video enabled, defaults to false |
| **`custom`**  | <code><a href="#record">Record</a>&lt; string, \| string \| boolean \| number \| null \| <a href="#record">Record</a>&lt;string, string \| boolean \| number \| null&gt; \| string[] \| boolean[] \| number[] &gt;</code> | Custom data to be passed to the call                            |


#### CallEvent

| Prop          | Type                                                                                                                                                                                                                      | Description                                                    |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| **`callId`**  | <code>string</code>                                                                                                                                                                                                       | ID of the call                                                 |
| **`state`**   | <code><a href="#callstate">CallState</a></code>                                                                                                                                                                           | Current state of the call                                      |
| **`userId`**  | <code>string</code>                                                                                                                                                                                                       | User ID of the participant in the call who triggered the event |
| **`reason`**  | <code>string</code>                                                                                                                                                                                                       | Reason for the call state change, if applicable                |
| **`caller`**  | <code><a href="#callmember">CallMember</a></code>                                                                                                                                                                         | Information about the caller (for incoming calls)              |
| **`members`** | <code>CallMember[]</code>                                                                                                                                                                                                 | List of call members                                           |
| **`custom`**  | <code><a href="#record">Record</a>&lt; string, \| string \| boolean \| number \| null \| <a href="#record">Record</a>&lt;string, string \| boolean \| number \| null&gt; \| string[] \| boolean[] \| number[] &gt;</code> |                                                                |
| **`count`**   | <code>number</code>                                                                                                                                                                                                       |                                                                |


#### CallState

<a href="#callstate">CallState</a> is the current state of the call
as seen by an SFU.

| Prop                   | Type                                                          | Description                                                                                                                                                                                                                                                                   |
| ---------------------- | ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`participants`**     | <code>Participant[]</code>                                    | participants is the list of participants in the call. In large calls, the list could be truncated in which case, the list of participants contains fewer participants than the counts returned in participant_count. Anonymous participants are **NOT** included in the list. |
| **`startedAt`**        | <code><a href="#timestamp">Timestamp</a></code>               | started_at is the time the call session actually started.                                                                                                                                                                                                                     |
| **`participantCount`** | <code><a href="#participantcount">ParticipantCount</a></code> | participant_count contains the summary of the counts.                                                                                                                                                                                                                         |
| **`pins`**             | <code>Pin[]</code>                                            | the list of pins in the call. Pins are ordered in descending order (most important first).                                                                                                                                                                                    |


#### Participant

those who are online in the call

| Prop                    | Type                                                            | Description                   |
| ----------------------- | --------------------------------------------------------------- | ----------------------------- |
| **`userId`**            | <code>string</code>                                             |                               |
| **`sessionId`**         | <code>string</code>                                             |                               |
| **`publishedTracks`**   | <code>TrackType[]</code>                                        | map of track id to track type |
| **`joinedAt`**          | <code><a href="#timestamp">Timestamp</a></code>                 |                               |
| **`trackLookupPrefix`** | <code>string</code>                                             |                               |
| **`connectionQuality`** | <code><a href="#connectionquality">ConnectionQuality</a></code> |                               |
| **`isSpeaking`**        | <code>boolean</code>                                            |                               |
| **`isDominantSpeaker`** | <code>boolean</code>                                            |                               |
| **`audioLevel`**        | <code>number</code>                                             |                               |
| **`name`**              | <code>string</code>                                             |                               |
| **`image`**             | <code>string</code>                                             |                               |
| **`custom`**            | <code><a href="#struct">Struct</a></code>                       |                               |
| **`roles`**             | <code>string[]</code>                                           |                               |


#### Timestamp

A <a href="#timestamp">Timestamp</a> represents a point in time independent of any time zone or local
calendar, encoded as a count of seconds and fractions of seconds at
nanosecond resolution. The count is relative to an epoch at UTC midnight on
January 1, 1970, in the proleptic Gregorian calendar which extends the
Gregorian calendar backwards to year one.

All minutes are 60 seconds long. Leap seconds are "smeared" so that no leap
second table is needed for interpretation, using a [24-hour linear
smear](https://developers.google.com/time/smear).

The range is from 0001-01-01T00:00:00Z to 9999-12-31T23:59:59.999999999Z. By
restricting to that range, we ensure that we can convert to and from [RFC
3339](https://www.ietf.org/rfc/rfc3339.txt) date strings.

# Examples

Example 1: Compute <a href="#timestamp">Timestamp</a> from POSIX `time()`.

    <a href="#timestamp">Timestamp</a> timestamp;
    timestamp.set_seconds(time(NULL));
    timestamp.set_nanos(0);

Example 2: Compute <a href="#timestamp">Timestamp</a> from POSIX `gettimeofday()`.

    struct timeval tv;
    gettimeofday(&tv, NULL);

    <a href="#timestamp">Timestamp</a> timestamp;
    timestamp.set_seconds(tv.tv_sec);
    timestamp.set_nanos(tv.tv_usec * 1000);

Example 3: Compute <a href="#timestamp">Timestamp</a> from Win32 `GetSystemTimeAsFileTime()`.

    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    UINT64 ticks = (((UINT64)ft.dwHighDateTime) &lt;&lt; 32) | ft.dwLowDateTime;

    // A Windows tick is 100 nanoseconds. Windows epoch 1601-01-01T00:00:00Z
    // is 11644473600 seconds before Unix epoch 1970-01-01T00:00:00Z.
    <a href="#timestamp">Timestamp</a> timestamp;
    timestamp.set_seconds((INT64) ((ticks / 10000000) - 11644473600LL));
    timestamp.set_nanos((INT32) ((ticks % 10000000) * 100));

Example 4: Compute <a href="#timestamp">Timestamp</a> from Java `System.currentTimeMillis()`.

    long millis = System.currentTimeMillis();

    <a href="#timestamp">Timestamp</a> timestamp = <a href="#timestamp">Timestamp</a>.newBuilder().setSeconds(millis / 1000)
        .setNanos((int) ((millis % 1000) * 1000000)).build();


Example 5: Compute <a href="#timestamp">Timestamp</a> from Java `Instant.now()`.

    Instant now = Instant.now();

    <a href="#timestamp">Timestamp</a> timestamp =
        <a href="#timestamp">Timestamp</a>.newBuilder().setSeconds(now.getEpochSecond())
            .setNanos(now.getNano()).build();


Example 6: Compute <a href="#timestamp">Timestamp</a> from current time in Python.

    timestamp = <a href="#timestamp">Timestamp</a>()
    timestamp.GetCurrentTime()

# JSON Mapping

In JSON format, the <a href="#timestamp">Timestamp</a> type is encoded as a string in the
[RFC 3339](https://www.ietf.org/rfc/rfc3339.txt) format. That is, the
format is "{year}-{month}-{day}T{hour}:{min}:{sec}[.{frac_sec}]Z"
where {year} is always expressed using four digits while {month}, {day},
{hour}, {min}, and {sec} are zero-padded to two digits each. The fractional
seconds, which can go up to 9 digits (i.e. up to 1 nanosecond resolution),
are optional. The "Z" suffix indicates the timezone ("UTC"); the timezone
is required. A proto3 JSON serializer should always use UTC (as indicated by
"Z") when printing the <a href="#timestamp">Timestamp</a> type and a proto3 JSON parser should be
able to accept both UTC and other timezones (as indicated by an offset).

For example, "2017-01-15T01:30:15.01Z" encodes 15.01 seconds past
01:30 UTC on January 15, 2017.

In JavaScript, one can convert a Date object to this format using the
standard
[toISOString()](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date/toISOString)
method. In Python, a standard `datetime.datetime` object can be converted
to this format using
[`strftime`](https://docs.python.org/2/library/time.html#time.strftime) with
the time format spec '%Y-%m-%dT%H:%M:%S.%fZ'. Likewise, in Java, one can use
the Joda Time's [`ISODateTimeFormat.dateTime()`](
http://www.joda.org/joda-time/apidocs/org/joda/time/format/ISODateTimeFormat.html#dateTime%2D%2D
) to obtain a formatter capable of generating timestamps in this format.

| Prop          | Type                | Description                                                                                                                                                                                                       |
| ------------- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`seconds`** | <code>string</code> | Represents seconds of UTC time since Unix epoch 1970-01-01T00:00:00Z. Must be from 0001-01-01T00:00:00Z to 9999-12-31T23:59:59Z inclusive.                                                                        |
| **`nanos`**   | <code>number</code> | Non-negative fractions of a second at nanosecond resolution. Negative second values with fractions must still have non-negative nanos values that count forward in time. Must be from 0 to 999,999,999 inclusive. |


#### Struct

<a href="#struct">`Struct`</a> represents a structured data value, consisting of fields
which map to dynamically typed values. In some languages, `Struct`
might be supported by a native representation. For example, in
scripting languages like JS a struct is represented as an
object. The details of that representation are described together
with the proto support for the language.

The JSON representation for <a href="#struct">`Struct`</a> is JSON object.

| Prop         | Type                                                        | Description                                |
| ------------ | ----------------------------------------------------------- | ------------------------------------------ |
| **`fields`** | <code>{ [key: string]: <a href="#value">Value</a>; }</code> | Unordered map of dynamically typed values. |


#### Value

<a href="#value">`Value`</a> represents a dynamically typed value which can be either
null, a number, a string, a boolean, a recursive struct value, or a
list of values. A producer of value is expected to set one of these
variants. Absence of any variant indicates an error.

The JSON representation for <a href="#value">`Value`</a> is JSON value.

| Prop       | Type                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`kind`** | <code>{ oneofKind: 'nullValue'; nullValue: <a href="#nullvalue">NullValue</a>; } \| { oneofKind: 'numberValue'; numberValue: number; } \| { oneofKind: 'stringValue'; stringValue: string; } \| { oneofKind: 'boolValue'; boolValue: boolean; } \| { oneofKind: 'structValue'; structValue: <a href="#struct">Struct</a>; } \| { oneofKind: 'listValue'; listValue: <a href="#listvalue">ListValue</a>; } \| { oneofKind: undefined; }</code> |


#### ListValue

<a href="#listvalue">`ListValue`</a> is a wrapper around a repeated field of values.

The JSON representation for <a href="#listvalue">`ListValue`</a> is JSON array.

| Prop         | Type                 | Description                                 |
| ------------ | -------------------- | ------------------------------------------- |
| **`values`** | <code>Value[]</code> | Repeated field of dynamically typed values. |


#### ParticipantCount

| Prop            | Type                | Description                                                                    |
| --------------- | ------------------- | ------------------------------------------------------------------------------ |
| **`total`**     | <code>number</code> | Total number of participants in the call including the anonymous participants. |
| **`anonymous`** | <code>number</code> | Total number of anonymous participants in the call.                            |


#### Pin

| Prop            | Type                | Description                                                         |
| --------------- | ------------------- | ------------------------------------------------------------------- |
| **`userId`**    | <code>string</code> | the user to pin                                                     |
| **`sessionId`** | <code>string</code> | the user sesion_id to pin, if not provided, applies to all sessions |


#### CallMember

| Prop           | Type                | Description                   |
| -------------- | ------------------- | ----------------------------- |
| **`userId`**   | <code>string</code> | User ID of the member         |
| **`name`**     | <code>string</code> | Display name of the user      |
| **`imageURL`** | <code>string</code> | Profile image URL of the user |
| **`role`**     | <code>string</code> | Role of the user in the call  |


#### IncomingCallPayload

| Prop         | Type                                                                                                                                                                                                                      | Description                              |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| **`cid`**    | <code>string</code>                                                                                                                                                                                                       | Full call CID (e.g. default:123)         |
| **`type`**   | <code>'incoming'</code>                                                                                                                                                                                                   | Event type (currently always "incoming") |
| **`caller`** | <code><a href="#callmember">CallMember</a></code>                                                                                                                                                                         | Information about the caller             |
| **`custom`** | <code><a href="#record">Record</a>&lt; string, \| string \| boolean \| number \| null \| <a href="#record">Record</a>&lt;string, string \| boolean \| number \| null&gt; \| string[] \| boolean[] \| number[] &gt;</code> | Custom data to be passed to the call     |

| Method               | Signature                                    | Description                             |
| -------------------- | -------------------------------------------- | --------------------------------------- |
| **getPluginVersion** | () =&gt; Promise&lt;{ version: string; }&gt; | Get the native Capacitor plugin version |


#### CameraEnabledResponse

| Prop          | Type                 |
| ------------- | -------------------- |
| **`enabled`** | <code>boolean</code> |


#### DynamicApiKeyResponse

| Prop                | Type                        | Description                             |
| ------------------- | --------------------------- | --------------------------------------- |
| **`apiKey`**        | <code>string \| null</code> | The dynamic API key if set, null if not |
| **`hasDynamicKey`** | <code>boolean</code>        | Whether a dynamic key is currently set  |


#### CurrentUserResponse

| Prop             | Type                 | Description                             |
| ---------------- | -------------------- | --------------------------------------- |
| **`userId`**     | <code>string</code>  | User ID of the current user             |
| **`name`**       | <code>string</code>  | Display name of the current user        |
| **`imageURL`**   | <code>string</code>  | Avatar URL of the current user          |
| **`isLoggedIn`** | <code>boolean</code> | Whether the user is currently logged in |


### Type Aliases


#### CallType

<code>'default' | 'audio' | 'audio_room' | 'livestream' | 'development'</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>


#### CallState

<code>'idle' | 'ringing' | 'joining' | 'reconnecting' | 'joined' | 'leaving' | 'left' | 'created' | 'session_started' | 'rejected' | 'participant_counts' | 'missed' | 'accepted' | 'ended' | 'camera_enabled' | 'camera_disabled' | 'speaker_enabled' | 'speaker_disabled' | 'microphone_enabled' | 'microphone_disabled' | 'outgoing_call_ended' | 'unknown'</code>


#### StreamCallLayout

<code>'grid' | 'spotlight' | 'dynamic' | 'fullScreen' | 'fullscreen'</code>


### Enums


#### TrackType

| Members                  | Value          |
| ------------------------ | -------------- |
| **`UNSPECIFIED`**        | <code>0</code> |
| **`AUDIO`**              | <code>1</code> |
| **`VIDEO`**              | <code>2</code> |
| **`SCREEN_SHARE`**       | <code>3</code> |
| **`SCREEN_SHARE_AUDIO`** | <code>4</code> |


#### ConnectionQuality

| Members           | Value          |
| ----------------- | -------------- |
| **`UNSPECIFIED`** | <code>0</code> |
| **`POOR`**        | <code>1</code> |
| **`GOOD`**        | <code>2</code> |
| **`EXCELLENT`**   | <code>3</code> |


#### NullValue

| Members          | Value          | Description |
| ---------------- | -------------- | ----------- |
| **`NULL_VALUE`** | <code>0</code> | Null value. |

</docgen-api>
