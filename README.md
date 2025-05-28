# @capgo/capacitor-stream-call
 <a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/Cap-go/capgo/main/assets/capgo_banner.png' alt='Capgo - Instant updates for capacitor'/></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin"> ➡️ Get Instant updates for your App with Capgo 🚀</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin"> Fix your annoying bug now, Hire a Capacitor expert 💪</a></h2>
</div>

WIP: We are actively working on this plugin. not yet ready for production.
Uses the https://getstream.io/ SDK to implement calling in Capacitor

## Install

```bash
npm install @capgo/capacitor-stream-call
npx cap sync
```

## Setting up Android StreamVideo apikey
1. Add your apikey to the Android project:
```
your_app/android/app/src/main/res/values/strings.xml
```

2. Add your apikey to the Android project:
```xml
<string name="CAPACITOR_STREAM_VIDEO_APIKEY">your_api_key</string>
```

## Setting up iOS StreamVideo apikey
1. Add your apikey to the iOS project:
```
your_app/ios/App/App/Info.plist
```

Add the following to the Info.plist file:
```xml
<dict>
  <key>CAPACITOR_STREAM_VIDEO_APIKEY</key>
  <string>n8wv8vjmucdw</string>
  <!-- other keys -->
</dict>
```

## Native Localization

### iOS

1. Add `Localizable.strings` and `Localizable.stringsdict` files to your Xcode project if you don't have them:
```
/App/App/en.lproj/Localizable.strings
/App/App/en.lproj/Localizable.stringsdict
```

2. Add new languages to your project in Xcode:
   - Open project settings
   - Select your project
   - Click "Info" tab
   - Under "Localizations" click "+"
   - Select the languages you want to add

3. Add the translations in your `Localizable.strings`:
```
// en.lproj/Localizable.strings
"stream.video.call.incoming" = "Incoming call from %@";
"stream.video.call.accept" = "Accept";
"stream.video.call.reject" = "Reject";
"stream.video.call.hangup" = "Hang up";
"stream.video.call.joining" = "Joining...";
"stream.video.call.reconnecting" = "Reconnecting...";
```

4. Configure the localization provider in your `AppDelegate.swift`:
```swift
import StreamVideo

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Set localization provider to use your app's bundle
        Appearance.localizationProvider = { key, table in
            Bundle.main.localizedString(forKey: key, value: nil, table: table)
        }
        return true
    }
}
```

You can find all available localization keys in the [StreamVideo SDK repository](https://github.com/GetStream/stream-video-swift/blob/main/Sources/StreamVideoSwiftUI/Resources/en.lproj/Localizable.strings).

### Android
1. Create string resources in `/app/src/main/res/values/strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="stream_video_call_incoming">Incoming call from %1$s</string>
    <string name="stream_video_call_accept">Accept</string>
    <string name="stream_video_call_reject">Reject</string>
    <string name="stream_video_call_hangup">Hang up</string>
    <string name="stream_video_call_joining">Joining...</string>
    <string name="stream_video_call_reconnecting">Reconnecting...</string>
</resources>
```

2. Add translations for other languages in their respective folders (e.g., `/app/src/main/res/values-fr/strings.xml`):
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="stream_video_call_incoming">Appel entrant de %1$s</string>
    <string name="stream_video_call_accept">Accepter</string>
    <string name="stream_video_call_reject">Refuser</string>
    <string name="stream_video_call_hangup">Raccrocher</string>
    <string name="stream_video_call_joining">Connexion...</string>
    <string name="stream_video_call_reconnecting">Reconnexion...</string>
</resources>
```

The SDK will automatically use the system language and these translations.

## API

<docgen-index>

* [`login(...)`](#login)
* [`logout()`](#logout)
* [`call(...)`](#call)
* [`endCall()`](#endcall)
* [`setMicrophoneEnabled(...)`](#setmicrophoneenabled)
* [`setCameraEnabled(...)`](#setcameraenabled)
* [`addListener('callEvent', ...)`](#addlistenercallevent-)
* [`removeAllListeners()`](#removealllisteners)
* [`acceptCall()`](#acceptcall)
* [`rejectCall()`](#rejectcall)
* [`isCameraEnabled()`](#iscameraenabled)
* [`getCallStatus()`](#getcallstatus)
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


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

Remove all event listeners

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


### Interfaces


#### SuccessResponse

| Prop          | Type                 | Description                          |
| ------------- | -------------------- | ------------------------------------ |
| **`success`** | <code>boolean</code> | Whether the operation was successful |


#### LoginOptions

| Prop             | Type                | Description                                             |
| ---------------- | ------------------- | ------------------------------------------------------- |
| **`token`**      | <code>string</code> | Stream Video API token                                  |
| **`userId`**     | <code>string</code> | User ID for the current user                            |
| **`name`**       | <code>string</code> | Display name for the current user                       |
| **`imageURL`**   | <code>string</code> | Optional avatar URL for the current user                |
| **`apiKey`**     | <code>string</code> | Stream Video API key                                    |
| **`magicDivId`** | <code>string</code> | ID of the HTML element where the video will be rendered |


#### CallOptions

| Prop          | Type                                          | Description                                                                                                                                                                         |
| ------------- | --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`userIds`** | <code>string[]</code>                         | User ID of the person to call                                                                                                                                                       |
| **`type`**    | <code><a href="#calltype">CallType</a></code> | Type of call, defaults to 'default'                                                                                                                                                 |
| **`ring`**    | <code>boolean</code>                          | Whether to ring the other user, defaults to true                                                                                                                                    |
| **`team`**    | <code>string</code>                           | Team name to call                                                                                                                                                                   |
| **`video`**   | <code>boolean</code>                          | Whether to use the 'video' call type. This will change the call type within CallKit iOS, but it will not change the call type within Android. !!! This is not supported on Android. |


#### CallEvent

| Prop         | Type                                            | Description                                                    |
| ------------ | ----------------------------------------------- | -------------------------------------------------------------- |
| **`callId`** | <code>string</code>                             | ID of the call                                                 |
| **`state`**  | <code><a href="#callstate">CallState</a></code> | Current state of the call                                      |
| **`userId`** | <code>string</code>                             | User ID of the participant in the call who triggered the event |
| **`reason`** | <code>string</code>                             | Reason for the call state change, if applicable                |


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


#### CameraEnabledResponse

| Prop          | Type                 |
| ------------- | -------------------- |
| **`enabled`** | <code>boolean</code> |


### Type Aliases


#### CallType

<code>'default' | 'audio_room' | 'livestream' | 'development'</code>


#### CallState

<code>'idle' | 'ringing' | 'joining' | 'reconnecting' | 'joined' | 'leaving' | 'left' | 'created' | 'session_started' | 'rejected' | 'missed' | 'accepted' | 'ended' | 'unknown'</code>


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
