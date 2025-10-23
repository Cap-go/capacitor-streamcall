import { WebPlugin } from '@capacitor/core';
import type { AllClientEvents, Call, CallResponse, StreamVideoParticipant } from '@stream-io/video-client';
import { CallingState, StreamVideoClient } from '@stream-io/video-client';

import type {
  CallOptions,
  StreamCallPlugin,
  SuccessResponse,
  LoginOptions,
  CameraEnabledResponse,
  CallEvent,
  CallState,
  CallMember,
  DynamicApiKeyResponse,
  CurrentUserResponse,
} from './definitions';

export class StreamCallWeb extends WebPlugin implements StreamCallPlugin {
  private client?: StreamVideoClient;
  private currentCall?: Call;
  private callStateSubscription?: { unsubscribe: () => void };
  private incomingCall?: CallResponse;
  private outgoingCall?: string;
  private magicDivId?: string;
  private videoBindings: Map<string, () => void> = new Map();
  private audioBindings: Map<string, () => void> = new Map();
  private participantJoinedListener?: (event: { participant?: { sessionId: string } }) => void;
  private participantLeftListener?: (event: { participant?: { sessionId: string } }) => void;
  private participantResponses: Map<string, string> = new Map();
  private callMembersExpected: Map<string, number> = new Map();
  private currentUser?: { userId: string; name: string; imageURL?: string };
  //  private currentActiveCallId?: string;

  private setupCallRingListener() {
    this.client?.off('call.ring', this.ringCallback);
    this.client?.off('call.session_started', this.callSessionStartedCallback);

    this.client?.on('call.ring', this.ringCallback);
    this.client?.on('call.session_started', this.callSessionStartedCallback);
  }

  private setupCallEventListeners() {
    // Clear previous listeners if any
    this.client?.off('call.rejected', this.callRejectedCallback);
    this.client?.off('call.accepted', this.callAcceptedCallback);
    this.client?.off('call.missed', this.callMissedCallback);
    // Register event listeners
    this.client?.on('call.rejected', this.callRejectedCallback);
    this.client?.on('call.accepted', this.callAcceptedCallback);
    this.client?.on('call.missed', this.callMissedCallback);
  }

  private ringCallback = (event: AllClientEvents['call.ring']) => {
    console.log('Call ringing', event, this.currentCall);
    this.incomingCall = event.call;

    // Extract caller information
    let caller: CallMember | undefined;

    if (event.call?.created_by) {
      caller = {
        userId: event.call.created_by.id,
        name: event.call.created_by.name,
        imageURL: event.call.created_by.image,
        role: event.call.created_by.role,
      };
    }

    // if (!this.currentCall) {
    console.log('Creating new call', event.call.id);
    this.currentCall = this.client?.call(event.call.type, event.call.id);
    // this.currentActiveCallId = this.currentCall?.cid;
    setTimeout(() => {
      this.notifyListeners('callEvent', {
        callId: event.call.id,
        state: CallingState.RINGING,
        caller,
      });
    }, 100);
    // Clear previous responses when a new call starts
    this.participantResponses.clear();
    // }
    if (this.currentCall) {
      console.log('Call found', this.currentCall.id);
      this.callStateSubscription = this.currentCall?.state.callingState$.subscribe((s) => {
        console.log('Call state', s);
        if (s === CallingState.JOINED) {
          this.setupParticipantListener();
          this.setupCallEventListeners();
        } else if (s === CallingState.LEFT || s === CallingState.RECONNECTING_FAILED) {
          this.cleanupCall();
        }
        if (this.outgoingCall && s === CallingState.RINGING) {
          this.outgoingCall = undefined;
        } else {
          this.notifyListeners('callEvent', { callId: this.currentCall?.id, state: s });
        }
      });
    }
  };

  private setupParticipantListener() {
    // Subscribe to participant changes
    this.incomingCall = undefined;
    if (!this.currentCall) return;

    this.participantJoinedListener = (event) => {
      if (this.magicDivId && event.participant) {
        const magicDiv = document.getElementById(this.magicDivId);
        if (magicDiv && this.currentCall) {
          this.setupParticipantVideo(this.currentCall, event.participant as StreamVideoParticipant, magicDiv);
          this.setupParticipantAudio(this.currentCall, event.participant as StreamVideoParticipant, magicDiv);
        }
      }
    };

    this.participantLeftListener = (event) => {
      if (this.magicDivId && event.participant) {
        const videoId = `video-${event.participant.sessionId}`;
        const audioId = `audio-${event.participant.sessionId}`;

        // Remove video element
        const videoEl = document.getElementById(videoId) as HTMLVideoElement;
        if (videoEl) {
          const unbindVideo = this.videoBindings.get(videoId);
          if (unbindVideo) {
            unbindVideo();
            this.videoBindings.delete(videoId);
          }
          const tracks = videoEl.srcObject as MediaStream;
          if (tracks) {
            tracks.getTracks().forEach((track) => {
              track.stop();
            });
          }
          videoEl.srcObject = null;
          videoEl.remove();
        }

        // Remove audio element
        const audioEl = document.getElementById(audioId) as HTMLAudioElement;
        if (audioEl) {
          const unbindAudio = this.audioBindings.get(audioId);
          if (unbindAudio) {
            unbindAudio();
            this.audioBindings.delete(audioId);
          }
          const tracks = audioEl.srcObject as MediaStream;
          if (tracks) {
            tracks.getTracks().forEach((track) => {
              track.stop();
            });
          }
          audioEl.srcObject = null;
          audioEl.remove();
        }
      }

      // Check if we're the only participant left in the call
      if (this.currentCall?.state.session) {
        // Get the remaining participants count (we need to subtract 1 as we haven't been removed from the list yet)
        const remainingParticipants = this.currentCall.state.session.participants.length - 1;

        // If we're the only one left, end the call
        if (remainingParticipants <= 1) {
          console.log(`We are left solo in a call. Ending. cID: ${this.currentCall.cid}`);

          // End the call
          this.currentCall.leave();

          // Clean up resources
          const callCid = this.currentCall.cid;

          // Invalidate and remove timer
          const callState = this.callStates?.get(callCid);
          if (callState?.timer) {
            clearInterval(callState.timer);
          }

          // Remove from callStates
          this.callStates?.delete(callCid);

          // Reset the current call
          this.currentCall = undefined;
          // this.currentActiveCallId = undefined;

          // Clean up
          this.cleanupCall();

          console.log(`Cleaned up resources for ended call: ${callCid}`);

          // Notify that the call has ended
          this.notifyListeners('callEvent', {
            callId: callCid,
            state: 'left',
            reason: 'participant_left',
          });
        }
      }
    };

    this.currentCall.on('participantJoined', this.participantJoinedListener);
    this.currentCall.on('participantLeft', this.participantLeftListener);

    // Setup initial participants
    const participants = this.currentCall.state.participants;
    if (this.magicDivId) {
      const magicDiv = document.getElementById(this.magicDivId);
      if (magicDiv) {
        participants.forEach((participant: StreamVideoParticipant) => {
          if (this.currentCall) {
            this.setupParticipantVideo(this.currentCall, participant, magicDiv);
            this.setupParticipantAudio(this.currentCall, participant, magicDiv);
          }
        });
      }
    }
  }

  private setupParticipantVideo(call: Call, participant: StreamVideoParticipant, container: HTMLElement) {
    const id = `video-${participant.sessionId}`;
    if (!document.getElementById(id)) {
      const videoEl = document.createElement('video');
      videoEl.id = id;
      videoEl.style.width = '100%';
      videoEl.style.maxWidth = '300px';
      videoEl.style.aspectRatio = '16/9';
      container.appendChild(videoEl);

      const unbind = call.bindVideoElement(videoEl, participant.sessionId, 'videoTrack');
      if (unbind) this.videoBindings.set(id, unbind);
    }
  }

  private setupParticipantAudio(call: Call, participant: StreamVideoParticipant, container: HTMLElement) {
    if (participant.isLocalParticipant) return;

    const id = `audio-${participant.sessionId}`;
    if (!document.getElementById(id)) {
      const audioEl = document.createElement('audio');
      audioEl.id = id;
      container.appendChild(audioEl);

      const unbind = call.bindAudioElement(audioEl, participant.sessionId);
      if (unbind) this.audioBindings.set(id, unbind);
    }
  }

  private callSessionStartedCallback = (event: AllClientEvents['call.session_started']) => {
    console.log('Call created (session started)', event);
    if (event.call?.session?.participants) {
      // Store the number of expected participants for this call
      const callCid = event.call.cid;
      const memberCount = event.call.session.participants.length;
      console.log(`Call ${callCid} created with ${memberCount} members`);
      this.callMembersExpected.set(callCid, memberCount);

      // Store call members in callStates
      this.callStates.set(callCid, {
        members: event.call.session.participants.map((p) => ({ user_id: p.user?.id || '' })),
        participantResponses: new Map(),
        expectedMemberCount: memberCount,
        createdAt: new Date(),
      });

      // Start a timeout task that runs every second
      const timeoutTask = setInterval(() => this.checkCallTimeout(callCid), 1000);

      // Update the callState with the timeout task
      const callState = this.callStates.get(callCid);
      if (callState) {
        callState.timer = timeoutTask;
        this.callStates.set(callCid, callState);
      }
    }
  };

  private callRejectedCallback = (event: AllClientEvents['call.rejected']) => {
    console.log('Call rejected', event);
    if (event.user?.id) {
      this.participantResponses.set(event.user.id, 'rejected');

      // Update the combined callStates map
      const callState = this.callStates.get(event.call_cid);
      if (callState) {
        callState.participantResponses.set(event.user.id, 'rejected');
        this.callStates.set(event.call_cid, callState);
      }

      this.notifyListeners('callEvent', {
        callId: event.call_cid,
        state: 'rejected',
        userId: event.user.id,
      });

      this.checkAllParticipantsResponded();
    }
  };

  private callAcceptedCallback = (event: AllClientEvents['call.accepted']) => {
    console.log('Call accepted', event);
    if (event.user?.id) {
      this.participantResponses.set(event.user.id, 'accepted');

      // Update the combined callStates map
      const callState = this.callStates.get(event.call_cid);
      if (callState) {
        callState.participantResponses.set(event.user.id, 'accepted');

        // If someone accepted, clear the timer as we don't need to check anymore
        if (callState.timer) {
          clearInterval(callState.timer);
          callState.timer = undefined;
        }

        this.callStates.set(event.call_cid, callState);
      }

      this.notifyListeners('callEvent', {
        callId: event.call_cid,
        state: 'accepted',
        userId: event.user.id,
      });
    }
  };

  private callMissedCallback = (event: AllClientEvents['call.missed']) => {
    console.log('Call missed', event);
    if (event.user?.id) {
      this.participantResponses.set(event.user.id, 'missed');

      // Update the combined callStates map
      const callState = this.callStates.get(event.call_cid);
      if (callState) {
        callState.participantResponses.set(event.user.id, 'missed');
        this.callStates.set(event.call_cid, callState);
      }

      this.notifyListeners('callEvent', {
        callId: event.call_cid,
        state: 'missed',
        userId: event.user.id,
      });

      this.checkAllParticipantsResponded();
    }
  };

  // Add a combined map for call states, mirroring the iOS implementation
  private callStates: Map<
    string,
    {
      members: { user_id: string }[];
      participantResponses: Map<string, string>;
      expectedMemberCount: number;
      createdAt: Date;
      timer?: ReturnType<typeof setInterval>;
    }
  > = new Map();

  private checkCallTimeout(callCid: string) {
    const callState = this.callStates.get(callCid);
    if (!callState) return;

    // Calculate time elapsed since call creation
    const now = new Date();
    const elapsedSeconds = (now.getTime() - callState.createdAt.getTime()) / 1000;

    // Check if 30 seconds have passed
    if (elapsedSeconds >= 30) {
      console.log(`Call ${callCid} has timed out after ${elapsedSeconds} seconds`);

      // Check if anyone has accepted
      const hasAccepted = Array.from(callState.participantResponses.values()).some(
        (response) => response === 'accepted',
      );

      if (!hasAccepted) {
        console.log(`No one accepted call ${callCid}, marking all non-responders as missed`);

        // Mark all members who haven't responded as "missed"
        callState.members.forEach((member) => {
          if (!callState.participantResponses.has(member.user_id)) {
            callState.participantResponses.set(member.user_id, 'missed');
            this.participantResponses.set(member.user_id, 'missed');

            this.notifyListeners('callEvent', {
              callId: callCid,
              state: 'missed',
              userId: member.user_id,
            });
          }
        });

        // End the call
        if (this.currentCall && this.currentCall.cid === callCid) {
          this.currentCall.leave();
        }

        // Clear the timeout task
        if (callState.timer) {
          clearInterval(callState.timer);
          callState.timer = undefined;
        }

        // Remove from callStates
        this.callStates.delete(callCid);

        // Clean up
        this.cleanupCall();

        // Notify that the call has ended
        this.notifyListeners('callEvent', {
          callId: callCid,
          state: 'ended',
          reason: 'timeout',
        });
      }
    }
  }

  private checkAllParticipantsResponded() {
    if (!this.currentCall) return;

    const callCid = this.currentCall.cid;
    const totalParticipants = this.callMembersExpected.get(callCid);

    if (!totalParticipants) {
      console.log(`No expected participant count found for call: ${callCid}`);
      return;
    }

    console.log(`Total expected participants: ${totalParticipants}`);

    // Count rejections and misses
    let rejectedOrMissedCount = 0;
    this.participantResponses.forEach((response) => {
      if (response === 'rejected' || response === 'missed') {
        rejectedOrMissedCount++;
      }
    });

    console.log(`Participants responded: ${this.participantResponses.size}/${totalParticipants}`);
    console.log(`Rejected or missed: ${rejectedOrMissedCount}`);

    const allResponded = this.participantResponses.size >= totalParticipants;
    const allRejectedOrMissed =
      allResponded &&
      Array.from(this.participantResponses.values()).every(
        (response) => response === 'rejected' || response === 'missed',
      );

    // If all participants have rejected or missed the call
    if (allResponded && allRejectedOrMissed) {
      console.log('All participants have rejected or missed the call');

      // End the call
      this.currentCall.leave();

      // Clean up the timer if exists in callStates
      const callState = this.callStates.get(callCid);
      if (callState?.timer) {
        clearInterval(callState.timer);
      }

      // Remove from callStates
      this.callStates.delete(callCid);

      // Clear the responses
      this.participantResponses.clear();

      // Clean up
      this.cleanupCall();

      // Notify that the call has ended
      this.notifyListeners('callEvent', {
        callId: callCid,
        state: 'ended',
        reason: 'all_rejected_or_missed',
      });
    }
  }

  private cleanupCall() {
    // First cleanup the call listeners
    if (this.currentCall) {
      if (this.participantJoinedListener) {
        this.currentCall.off('participantJoined', this.participantJoinedListener);
        this.participantJoinedListener = undefined;
      }
      if (this.participantLeftListener) {
        this.currentCall.off('participantLeft', this.participantLeftListener);
        this.participantLeftListener = undefined;
      }
      this.callStateSubscription?.unsubscribe();
    }

    if (this.magicDivId) {
      const magicDiv = document.getElementById(this.magicDivId);
      if (magicDiv) {
        // Remove all video elements
        const videoElements = magicDiv.querySelectorAll('video');
        videoElements.forEach((video) => {
          const id = video.id;
          const unbind = this.videoBindings.get(id);
          if (unbind) {
            unbind();
            this.videoBindings.delete(id);
          }
          // Stop all tracks
          const tracks = (video as HTMLVideoElement).srcObject as MediaStream;
          if (tracks) {
            tracks.getTracks().forEach((track) => {
              track.stop();
              track.enabled = false;
            });
            video.srcObject = null;
          }
          video.remove();
        });

        // Remove all audio elements
        const audioElements = magicDiv.querySelectorAll('audio');
        audioElements.forEach((audio) => {
          const id = audio.id;
          const unbind = this.audioBindings.get(id);
          if (unbind) {
            unbind();
            this.audioBindings.delete(id);
          }
          // Stop all tracks
          const tracks = (audio as HTMLAudioElement).srcObject as MediaStream;
          if (tracks) {
            tracks.getTracks().forEach((track) => {
              track.stop();
              track.enabled = false;
            });
            audio.srcObject = null;
          }
          audio.remove();
        });

        // Clear the container
        while (magicDiv.firstChild) {
          magicDiv.removeChild(magicDiv.firstChild);
        }
      }
    }

    // Clear all bindings
    this.videoBindings.clear();
    this.audioBindings.clear();

    // Clear call references
    this.currentCall = undefined;
    this.incomingCall = undefined;
  }

  async login(options: LoginOptions): Promise<SuccessResponse> {
    this.client = StreamVideoClient.getOrCreateInstance({
      apiKey: options.apiKey,
      user: { id: options.userId, name: options.name, image: options.imageURL },
      token: options.token,
    });

    // Store current user data
    this.currentUser = {
      userId: options.userId,
      name: options.name,
      imageURL: options.imageURL,
    };

    this.magicDivId = options.magicDivId;
    this.setupCallRingListener();

    return { success: true };
  }

  async logout(): Promise<SuccessResponse> {
    if (!this.client) {
      console.log('No client', this.client);
      throw new Error('Client not initialized');
    }

    // Cleanup subscription
    this.callStateSubscription?.unsubscribe();
    this.callStateSubscription = undefined;

    await this.client.disconnectUser();
    this.client = undefined;
    this.currentUser = undefined;
    this.currentCall = undefined;
    return { success: true };
  }

  async call(options: CallOptions): Promise<SuccessResponse> {
    if (!this.client) {
      console.log('No client', this.client);
      throw new Error('Client not initialized - Please login first');
    }

    const call = this.client.call(options.type || 'default', crypto.randomUUID());
    const members = options.userIds.map((userId) => ({ user_id: userId }));
    if (this.client.streamClient.userID && !options.userIds.includes(this.client.streamClient.userID)) {
      members.push({ user_id: this.client.streamClient.userID });
    }
    await call.getOrCreate({ data: { members, team: options.team } });

    // Store the expected member count for this call
    // -1, because we don't count the caller themselves
    this.callMembersExpected.set(call.cid, members.length);
    console.log(`Setting expected members for call ${call.cid}: ${members.length}`);

    this.currentCall = call;
    if (options.ring) {
      this.outgoingCall = call.cid;
      await call.ring();
    }

    await call.join();
    return { success: true };
  }

  async endCall(): Promise<SuccessResponse> {
    if (!this.currentCall) {
      console.log('No active call', this.currentCall);
      throw new Error('No active call');
    }

    await this.currentCall.leave();
    this.currentCall = undefined;
    this.cleanupCall();

    return { success: true };
  }

  async setMicrophoneEnabled(options: { enabled: boolean }): Promise<SuccessResponse> {
    if (!this.currentCall) {
      console.log('No active call', this.currentCall);
      throw new Error('No active call');
    }

    if (options.enabled) {
      await this.currentCall.microphone.enable();
    } else {
      await this.currentCall.microphone.disable();
    }

    return { success: true };
  }

  async setCameraEnabled(options: { enabled: boolean }): Promise<SuccessResponse> {
    if (!this.currentCall) {
      console.log('No active call', this.currentCall);
      throw new Error('No active call');
    }

    if (options.enabled) {
      await this.currentCall.camera.enable();
    } else {
      await this.currentCall.camera.disable();
    }

    return { success: true };
  }

  async setSpeaker(options: { name: string }): Promise<SuccessResponse> {
    if (!this.currentCall) {
      console.log('No active call', this.currentCall);
      throw new Error('No active call');
    }

    await this.currentCall.speaker.select(options.name);

    return { success: true };
  }

  async switchCamera(options: { camera: 'front' | 'back' }): Promise<SuccessResponse> {
    if (!this.currentCall) {
      console.log('No active call', this.currentCall);
      throw new Error('No active call');
    }

    if (options.camera === 'front') {
      await this.currentCall.camera.selectDirection('front');
    } else {
      await this.currentCall.camera.selectDirection('back');
    }

    return { success: true };
  }

  async acceptCall(): Promise<SuccessResponse> {
    if (!this.incomingCall || !this.client) {
      console.log('No incoming call to accept', this.incomingCall, this.client);
      throw new Error('No incoming call to accept');
    }
    console.log('Accepting call', this.incomingCall);
    const call = this.client.call(this.incomingCall.type, this.incomingCall.id);
    this.currentCall = call;
    console.log('Joining call', call);
    await call.accept();
    await call.join();
    console.log('Joined call', call);
    this.notifyListeners('callEvent', { callId: call.id, state: CallingState.JOINED });
    this.setupParticipantListener();
    return { success: true };
  }

  async rejectCall(): Promise<SuccessResponse> {
    if (!this.incomingCall || !this.client) {
      console.log('No incoming call to reject', this.incomingCall, this.client);
      throw new Error('No incoming call to reject');
    }
    console.log('Rejecting call', this.incomingCall);
    const call: Call = this.client.call(this.incomingCall.type, this.incomingCall.id);
    console.log('Leaving call', call);
    await call.reject();
    this.incomingCall = undefined;
    console.log('Rejected call', call);
    this.notifyListeners('callEvent', { callId: call.id, state: CallingState.LEFT });
    this.cleanupCall();
    return { success: true };
  }

  async isCameraEnabled(): Promise<CameraEnabledResponse> {
    if (!this.currentCall) {
      console.log('No active call', this.currentCall);
      throw new Error('No active call');
    }
    const enabled = await this.currentCall.camera.enabled;
    return { enabled };
  }

  async getCallInfo(options: { callId: string }): Promise<CallEvent> {
    if (!this.currentCall) {
      throw new Error('No active call');
    }

    if (this.currentCall.id !== options.callId) {
      throw new Error('Call ID does not match active call');
    }

    const callingState = this.currentCall.state.callingState;
    let state: CallState;

    switch (callingState) {
      case CallingState.IDLE:
        state = 'idle';
        break;
      case CallingState.RINGING:
        state = 'ringing';
        break;
      case CallingState.JOINING:
        state = 'joining';
        break;
      case CallingState.RECONNECTING:
        state = 'reconnecting';
        break;
      case CallingState.JOINED:
        state = 'joined';
        break;
      case CallingState.LEFT:
        state = 'left';
        break;
      default:
        state = 'unknown';
    }

    // Try to get caller information from the call
    let caller: CallMember | undefined;
    let members: CallMember[] = [];

    try {
      // Get call details
      const callDetails = await this.currentCall.get();

      if (callDetails?.call?.created_by) {
        caller = {
          userId: callDetails.call.created_by.id,
          name: callDetails.call.created_by.name,
          imageURL: callDetails.call.created_by.image,
          role: callDetails.call.created_by.role,
        };
      }

      // Get current participants
      if (this.currentCall.state.participants) {
        members = this.currentCall.state.participants.map((participant: StreamVideoParticipant) => ({
          userId: participant.userId,
          name: participant.name,
          imageURL: participant.image,
          role: participant.roles?.[0],
        }));
      }
    } catch (error) {
      console.warn('Failed to get call details:', error);
    }

    return {
      callId: this.currentCall.id,
      state,
      caller,
      members,
    };
  }

  async getCallStatus(): Promise<CallEvent> {
    if (!this.currentCall) {
      throw new Error('No active call');
    }

    const callingState = this.currentCall.state.callingState;
    let state: CallState;

    switch (callingState) {
      case CallingState.IDLE:
        state = 'idle';
        break;
      case CallingState.RINGING:
        state = 'ringing';
        break;
      case CallingState.JOINING:
        state = 'joining';
        break;
      case CallingState.RECONNECTING:
        state = 'reconnecting';
        break;
      case CallingState.JOINED:
        state = 'joined';
        break;
      case CallingState.LEFT:
        state = 'left';
        break;
      default:
        state = 'unknown';
    }

    return {
      callId: this.currentCall.id,
      state,
    };
  }

  async setDynamicStreamVideoApikey(_options: { apiKey: string }): Promise<SuccessResponse> {
    throw new Error('setDynamicStreamVideoApikey is not implemented on web platform');
  }

  async getDynamicStreamVideoApikey(): Promise<DynamicApiKeyResponse> {
    throw new Error('getDynamicStreamVideoApikey is not implemented on web platform');
  }

  async getCurrentUser(): Promise<CurrentUserResponse> {
    if (this.currentUser && this.client) {
      return {
        userId: this.currentUser.userId,
        name: this.currentUser.name,
        imageURL: this.currentUser.imageURL || '',
        isLoggedIn: true,
      };
    } else {
      return {
        userId: '',
        name: '',
        imageURL: '',
        isLoggedIn: false,
      };
    }
  }

  async getPluginVersion(): Promise<{ version: string }> {
    return { version: 'web' };
  }
}
