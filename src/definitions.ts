/**
 * @interface LoginOptions
 * @description Configuration options for logging into the Stream Video service
 * @property {string} token - Stream Video API token for authentication
 * @property {string} userId - Unique identifier for the current user
 * @property {string} name - Display name for the current user
 * @property {string} [imageURL] - Avatar URL for the current user
 * @property {string} apiKey - Stream Video API key for your application
 * @property {string} [magicDivId] - DOM element ID where video will be rendered
 */
export interface LoginOptions {
  /** Stream Video API token */
  token: string;
  /** User ID for the current user */
  userId: string;
  /** Display name for the current user */
  name: string;
  /** Optional avatar URL for the current user */
  imageURL?: string;
  /** Stream Video API key */
  apiKey: string;
  /** ID of the HTML element where the video will be rendered */
  magicDivId?: string;
  pushNotificationsConfig?: PushNotificationsConfig;
}

export interface PushNotificationsConfig {
  pushProviderName: string;
  voipProviderName: string;
}

/**
 * @typedef CallState
 * @description Represents all possible call states from API and UI
 */
export type CallState =
  // User-facing states
  | 'idle'
  | 'ringing'
  | 'joining'
  | 'reconnecting'
  | 'joined'
  | 'leaving'
  | 'left'
  // Event-specific states
  | 'created'
  | 'session_started'
  | 'rejected'
  | 'participant_counts'
  | 'missed'
  | 'accepted'
  | 'ended'
  | 'camera_enabled'
  | 'camera_disabled'
  | 'speaker_enabled'
  | 'speaker_disabled'
  | 'microphone_enabled'
  | 'microphone_disabled'
  | 'outgoing_call_ended'
  | 'unknown';

/**
 * @typedef CallType
 * @description Represents the pre-defined types of a call.
 * - `default`: Simple 1-1 or group video calling with sensible defaults. Video/audio enabled, backstage disabled. Admins/hosts have elevated permissions.
 * - `audio_room`: For audio-only spaces (like Clubhouse). Backstage enabled (requires `goLive`), pre-configured permissions for requesting to speak.
 * - `livestream`: For one-to-many streaming. Backstage enabled (requires `goLive`), access granted to all authenticated users.
 * - `development`: For testing ONLY. All permissions enabled, backstage disabled. **Not recommended for production.**
 */
export type CallType = 'default' | 'audio' | 'audio_room' | 'livestream' | 'development';

/**
 * @typedef StreamCallLayout
 * @description Layout modes matching the Stream React SDK; includes native aliases.
 */
export type StreamCallLayout = 'grid' | 'spotlight' | 'dynamic' | 'fullScreen' | 'fullscreen';

/**
 * @interface CallMember
 * @description Information about a call member/participant
 * @property {string} userId - User ID of the member
 * @property {string} [name] - Display name of the user
 * @property {string} [imageURL] - Profile image URL of the user
 * @property {string} [role] - Role of the user in the call
 */
export interface CallMember {
  /** User ID of the member */
  userId: string;
  /** Display name of the user */
  name?: string;
  /** Profile image URL of the user */
  imageURL?: string;
  /** Role of the user in the call */
  role?: string;
}

/**
 * @interface CallEvent
 * @description Event emitted when call state changes
 * @property {string} callId - Unique identifier of the call
 * @property {CallState} state - Current state of the call
 * @property {string} [userId] - User ID of the participant who triggered the event
 * @property {string} [reason] - Reason for the call state change
 * @property {CallMember} [caller] - Information about the caller (for incoming calls)
 * @property {CallMember[]} [members] - List of call members
 */
export interface CallEvent {
  /** ID of the call */
  callId: string;
  /** Current state of the call */
  state: CallState;
  /** User ID of the participant in the call who triggered the event */
  userId?: string;
  /** Reason for the call state change, if applicable */
  reason?: string;
  /** Information about the caller (for incoming calls) */
  caller?: CallMember;
  /** List of call members */
  members?: CallMember[];

  custom?: Record<
    string,
    | string
    | boolean
    | number
    | null
    | Record<string, string | boolean | number | null>
    | string[]
    | boolean[]
    | number[]
  >;

  count?: number;
}

export interface CameraEnabledResponse {
  enabled: boolean;
}

/**
 * @interface DynamicApiKeyResponse
 * @description Response from getDynamicStreamVideoApikey
 * @property {string|null} apiKey - The dynamic API key if set, null if not
 * @property {boolean} hasDynamicKey - Whether a dynamic key is currently set
 */
export interface DynamicApiKeyResponse {
  /** The dynamic API key if set, null if not */
  apiKey: string | null;
  /** Whether a dynamic key is currently set */
  hasDynamicKey: boolean;
}

/**
 * @interface CurrentUserResponse
 * @description Response from getCurrentUser containing user information
 * @property {string} userId - User ID of the current user
 * @property {string} name - Display name of the current user
 * @property {string} [imageURL] - Avatar URL of the current user
 * @property {boolean} isLoggedIn - Whether the user is currently logged in
 */
export interface CurrentUserResponse {
  /** User ID of the current user */
  userId: string;
  /** Display name of the current user */
  name: string;
  /** Avatar URL of the current user */
  imageURL?: string;
  /** Whether the user is currently logged in */
  isLoggedIn: boolean;
}

/**
 * @interface SuccessResponse
 * @description Standard response indicating operation success/failure
 * @property {boolean} success - Whether the operation succeeded
 */
export interface SuccessResponse {
  /** Whether the operation was successful */
  success: boolean;
  callId?: string;
}

/**
 * @interface CallOptions
 * @description Options for initiating a video call
 * @property {string[]} userIds - IDs of the users to call
 * @property {CallType} [type=default] - Type of call
 * @property {boolean} [ring=true] - Whether to send ring notification
 * @property {string} [team] - Team name to call
 */
export interface CallOptions {
  /** User ID of the person to call */
  userIds: string[];
  /** Type of call, defaults to 'default' */
  type?: CallType;
  /** Whether to ring the other user, defaults to true */
  ring?: boolean;
  /** Team name to call */
  team?: string;
  /** Whether to start the call with video enabled, defaults to false */
  video?: boolean;
  /** Custom data to be passed to the call */
  custom?: Record<
    string,
    | string
    | boolean
    | number
    | null
    | Record<string, string | boolean | number | null>
    | string[]
    | boolean[]
    | number[]
  >;
}

/**
 * @interface StreamCallPlugin
 * @description Capacitor plugin for Stream Video calling functionality
 */
export interface StreamCallPlugin {
  /**
   * Login to Stream Video service
   * @param {LoginOptions} options - Login configuration
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.login({
   *   token: 'your-token',
   *   userId: 'user-123',
   *   name: 'John Doe',
   *   apiKey: 'your-api-key'
   * });
   */
  login(options: LoginOptions): Promise<SuccessResponse>;

  /**
   * Logout from Stream Video service
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.logout();
   */
  logout(): Promise<SuccessResponse>;

  /**
   * Initiate a call to another user
   * @param {CallOptions} options - Call configuration
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.call({
   *   userId: 'user-456',
   *   type: 'video',
   *   ring: true
   * });
   */
  call(options: CallOptions): Promise<SuccessResponse>;

  /**
   * End the current call
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.endCall();
   */
  endCall(): Promise<SuccessResponse>;

  /**
   * Join an existing call
   * @param {{  callId: string, callType: string  }} options - Microphone state
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.joinCall({ callId: 'call001', callType: 'default' });
   */
  joinCall?(options: { callId: string; callType: string }): Promise<SuccessResponse>;

  /**
   * Enable or disable microphone
   * @param {{ enabled: boolean }} options - Microphone state
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.setMicrophoneEnabled({ enabled: false });
   */
  setMicrophoneEnabled(options: { enabled: boolean }): Promise<SuccessResponse>;

  /**
   * Enable or disable camera
   * @param {{ enabled: boolean }} options - Camera state
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.setCameraEnabled({ enabled: false });
   */
  setCameraEnabled(options: { enabled: boolean }): Promise<SuccessResponse>;

  /**
   * Add listener for call events
   * @param {'callEvent'} eventName - Name of the event to listen for
   * @param {(event: CallEvent) => void} listenerFunc - Callback function
   * @returns {Promise<{ remove: () => Promise<void> }>} Function to remove listener
   * @example
   * const listener = await StreamCall.addListener('callEvent', (event) => {
   *   console.log(`Call ${event.callId} is now ${event.state}`);
   * });
   */
  addListener(
    eventName: 'callEvent',
    listenerFunc: (event: CallEvent) => void,
  ): Promise<{ remove: () => Promise<void> }>;

  /**
   * Listen for lock-screen incoming call (Android only).
   * Fired when the app is shown by full-screen intent before user interaction.
   */
  addListener(
    eventName: 'incomingCall',
    listenerFunc: (event: IncomingCallPayload) => void,
  ): Promise<{ remove: () => Promise<void> }>;

  /**
   * Remove all event listeners
   * @returns {Promise<void>}
   * @example
   * await StreamCall.removeAllListeners();
   */
  removeAllListeners(): Promise<void>;

  /**
   * Enable bluetooth audio
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.enableBluetooth();
   */
  enableBluetooth?(): Promise<SuccessResponse>;

  /**
   * Accept an incoming call
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.acceptCall();
   */
  acceptCall(): Promise<SuccessResponse>;

  /**
   * Reject an incoming call
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.rejectCall();
   */
  rejectCall(): Promise<SuccessResponse>;

  /**
   * Check if camera is enabled
   * @returns {Promise<CameraEnabledResponse>} Camera enabled status
   * @example
   * const isCameraEnabled = await StreamCall.isCameraEnabled();
   * console.log(isCameraEnabled);
   */
  isCameraEnabled(): Promise<CameraEnabledResponse>;

  /**
   * Get the current call status
   * @returns {Promise<CallEvent>} Current call status as a CallEvent
   * @example
   * const callStatus = await StreamCall.getCallStatus();
   * console.log(callStatus);
   */
  getCallStatus(): Promise<CallEvent>;

  /**
   * Get the current ringing call
   * @returns {Promise<CallEvent>} Current ringing call status as a CallEvent
   * @example
   * const ringingCall = await StreamCall.getRingingCall();
   * console.log(ringingCall);
   */
  getRingingCall?(): Promise<CallEvent>;

  /**
   * Cycle through the available video layouts
   * @returns {Promise<{ newLayout: StreamCallLayout }>} The layout that is now active
   * @example
   * const { newLayout } = await StreamCall.toggleViews();
   * console.log(`Layout switched to ${newLayout}`);
   */
  toggleViews?(): Promise<{ newLayout: StreamCallLayout }>;

  /**
   * Set speakerphone on
   * @param {{ name: string }} options - Speakerphone name
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.setSpeaker({ name: 'speaker' });
   */
  setSpeaker(options: { name: string }): Promise<SuccessResponse>;

  /**
   * Switch camera
   * @param {{ camera: 'front' | 'back' }} options - Camera to switch to
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.switchCamera({ camera: 'back' });
   */
  switchCamera(options: { camera: 'front' | 'back' }): Promise<SuccessResponse>;

  /**
   * Get detailed information about an active call including caller details
   * @param options - Options containing the call ID
   */
  getCallInfo(options: { callId: string }): Promise<CallEvent>;

  /**
   * Set a dynamic Stream Video API key that overrides the static one
   * @param {{ apiKey: string }} options - The API key to set
   * @returns {Promise<SuccessResponse>} Success status
   * @example
   * await StreamCall.setDynamicStreamVideoApikey({ apiKey: 'new-api-key' });
   */
  setDynamicStreamVideoApikey(options: { apiKey: string }): Promise<SuccessResponse>;

  /**
   * Get the currently set dynamic Stream Video API key
   * @returns {Promise<DynamicApiKeyResponse>} The dynamic API key and whether it's set
   * @example
   * const result = await StreamCall.getDynamicStreamVideoApikey();
   * if (result.hasDynamicKey) {
   *   console.log('Dynamic API key:', result.apiKey);
   * } else {
   *   console.log('Using static API key from resources');
   * }
   */
  getDynamicStreamVideoApikey(): Promise<DynamicApiKeyResponse>;

  /**
   * Get the current user's information
   * @returns {Promise<CurrentUserResponse>} Current user information
   * @example
   * const currentUser = await StreamCall.getCurrentUser();
   * console.log(currentUser);
   */
  getCurrentUser(): Promise<CurrentUserResponse>;
}

/**
 * @interface IncomingCallPayload
 * @description Payload delivered with "incomingCall" event (Android lock-screen).
 * @property {string} cid - Call CID (type:id)
 * @property {string} type - Always "incoming" for this event
 * @property {CallMember} [caller] - Information about the caller
 */
export interface IncomingCallPayload {
  /** Full call CID (e.g. default:123) */
  cid: string;
  /** Event type (currently always "incoming") */
  type: 'incoming';
  /** Information about the caller */
  caller?: CallMember;
  /** Custom data to be passed to the call */
  custom?: Record<
    string,
    | string
    | boolean
    | number
    | null
    | Record<string, string | boolean | number | null>
    | string[]
    | boolean[]
    | number[]
  >;
}
