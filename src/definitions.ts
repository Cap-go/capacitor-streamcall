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
  | 'missed'
  | 'accepted'
  | 'ended'
  | 'unknown';

/**
 * @typedef CallType
 * @description Represents the pre-defined types of a call.
 * - `default`: Simple 1-1 or group video calling with sensible defaults. Video/audio enabled, backstage disabled. Admins/hosts have elevated permissions.
 * - `audio_room`: For audio-only spaces (like Clubhouse). Backstage enabled (requires `goLive`), pre-configured permissions for requesting to speak.
 * - `livestream`: For one-to-many streaming. Backstage enabled (requires `goLive`), access granted to all authenticated users.
 * - `development`: For testing ONLY. All permissions enabled, backstage disabled. **Not recommended for production.**
 */
export type CallType = 'default' | 'audio_room' | 'livestream' | 'development';

/**
 * @interface CallEvent
 * @description Event emitted when call state changes
 * @property {string} callId - Unique identifier of the call
 * @property {CallState} state - Current state of the call
 * @property {string} [userId] - User ID of the participant who triggered the event
 * @property {string} [reason] - Reason for the call state change
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
}

export interface CameraEnabledResponse {
  enabled: boolean;
}

/**
 * @interface SuccessResponse
 * @description Standard response indicating operation success/failure
 * @property {boolean} success - Whether the operation succeeded
 */
export interface SuccessResponse {
  /** Whether the operation was successful */
  success: boolean;
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
  /** Whether to use the 'video' call type. This will change the call type within CallKit iOS, but it will not change the call type within Android. !!! This is not supported on Android. */
  video?: boolean;
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
   * Remove all event listeners
   * @returns {Promise<void>}
   * @example
   * await StreamCall.removeAllListeners();
   */
  removeAllListeners(): Promise<void>;

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
}
