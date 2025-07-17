import { Component } from '@angular/core';
import { ChangeDetectorRef } from '@angular/core';
import { ToastController } from '@ionic/angular';
import { StreamCall } from '@capgo/capacitor-stream-call';
import { Capacitor } from '@capacitor/core';

@Component({
  selector: 'app-root',
  templateUrl: 'app.component.html',
  styleUrls: ['app.component.scss'],
  standalone: false,
})
export class AppComponent {
  constructor(private cdr: ChangeDetectorRef,
    private toastController: ToastController
  ) {}
  isInCall = false;
  isMuted = false;
  isCameraOff = false;
  isSpeakerOn = true;
  activeCamera: 'front' | 'back' = 'front';
  incomingCallId: string | null = null;
  incomingToast: HTMLIonToastElement | null = null;
  outgoingToast: HTMLIonToastElement | null = null;
  /** Lock-screen incoming call flag (Android) */
  isLockscreenIncoming = false;
  /** Outgoing call flag */
  isOutgoingCall = false;
  /** Caller information for incoming calls */
  callerInfo: { userId: string; name?: string; imageURL?: string } | null = null;
  /** Members information for outgoing calls */
  callMembers: Array<{ userId: string; name?: string; imageURL?: string }> = [];
  /** Flag to track if this is an incoming call to prevent state conflicts */
  private isIncomingCall = false;
  /** Current user ID for filtering */
  private currentUserId: string | null = null;

  private getCurrentUserId(): string | null {
    return this.currentUserId;
  }

  setCurrentUserId(userId: string) {
    this.currentUserId = userId;
  }

  async endCall() {
    this.isInCall = false;
    this.cdr.detectChanges();
    await StreamCall.endCall();
  }

  async endOutgoingCall() {
    this.isOutgoingCall = false;
    this.cdr.detectChanges();
    await StreamCall.endCall();
  }

  async toggleMute() {
    this.isMuted = !this.isMuted;
    await StreamCall.setMicrophoneEnabled({ enabled: !this.isMuted });
    this.cdr.detectChanges();
  }

  async toggleCamera() {
    this.isCameraOff = !this.isCameraOff;
    await StreamCall.setCameraEnabled({ enabled: !this.isCameraOff });
    this.cdr.detectChanges();
  }

  async toggleSpeaker() {
    this.isSpeakerOn = !this.isSpeakerOn;
    if (StreamCall.setSpeaker) {
      await StreamCall.setSpeaker({ name: this.isSpeakerOn ? 'speaker' : 'receiver' });
    }
    this.cdr.detectChanges();
  }

  async flipCamera() {
    this.activeCamera = this.activeCamera === 'front' ? 'back' : 'front';
    if (StreamCall.switchCamera) {
      await StreamCall.switchCamera({ camera: this.activeCamera });
    }
    this.cdr.detectChanges();
  }

  private async presentToast(message: string, color: 'success' | 'danger', position: 'top' | 'bottom' = 'top') {
    const toast = await this.toastController.create({
      message,
      duration: 2000,
      color,
      position
    });
    await toast.present();
  }

  async acceptCall() {
    if (!this.incomingCallId) return;
    
    try {
      await StreamCall.acceptCall();
      await this.presentToast('Call accepted', 'success');
    } catch (error) {
      console.error('Failed to accept call:', error);
      await this.presentToast('Failed to accept call', 'danger');
    }
  }

  async rejectCall() {
    if (!this.incomingCallId) return;
    
    try {
      await StreamCall.rejectCall();
      this.incomingCallId = null;
      await this.presentToast('Call rejected', 'success');
    } catch (error) {
      console.error('Failed to reject call:', error);
      await this.presentToast('Failed to reject call', 'danger');
    }
  }
  private async stopWaitingCallToast() {
    if (this.outgoingToast) {
      await this.outgoingToast.dismiss();
    }
  }

  private async presentWaitingCallToast() {
    if (this.outgoingToast) {
      await this.outgoingToast.dismiss();
    }
    this.outgoingToast = await this.toastController.create({
      message: 'Calling call...',
      position: 'top',
      buttons: [
        {
          side: 'end',
          icon: 'close',
          handler: () => {
            void this.rejectCall();
          }
        }
      ],
      duration: 0
    });
    await this.outgoingToast.present();
  }

  ngOnInit() {
    console.log('Making app transparent and initializing StreamCall');
    StreamCall.removeAllListeners();
    
    // Add transparent background style
    // const styleElement = document.createElement('style');
    // styleElement.id = 'magic_transparent_background';
    // styleElement.textContent = `
    //   :root {
    //     --ion-background-color: transparent !important;
    //   }
    //   ion-content {
    //     --background: transparent !important;
    //   }
    //   .ion-page {
    //     background: transparent !important;
    //   }
    // `;
    // document.head.appendChild(styleElement);

    // register event listeners
    console.log('Registering call event listener');
    StreamCall.addListener('callEvent', async(event: any) => {
      console.log('Call event received:', event);
      
      if (event.state === 'joined') {
        this.isInCall = true;
        this.isCameraOff = false;
        this.isMuted = false;
        this.isSpeakerOn = true;
        this.isLockscreenIncoming = false;
        this.isOutgoingCall = false;
        this.isIncomingCall = false;
        this.callerInfo = null; // Clear caller info when call starts
        this.callMembers = []; // Clear members info when call starts
        console.log('Call started', event);
        setTimeout(async () => {
          await this.incomingToast?.dismiss();
          const cameraEnabled = await StreamCall.isCameraEnabled();
          this.isCameraOff = !cameraEnabled.enabled;
          await this.presentToast('Call started', 'success', 'bottom');
          await this.stopWaitingCallToast();
          this.cdr.detectChanges();
        }, 1000);
      } else if (event.state === 'left') {
        this.isInCall = false;
        this.isLockscreenIncoming = false;
        this.isOutgoingCall = false;
        this.isIncomingCall = false;
        this.callerInfo = null; // Clear caller info when call ends
        this.callMembers = []; // Clear members info when call ends
        console.log('Call ended', event);
        await this.presentToast('Call ended', 'success', 'bottom');
        this.cdr.detectChanges();
      } else if (event.state === 'rejected') {
        this.isOutgoingCall = false;
        this.isIncomingCall = false;
        this.callerInfo = null; // Clear caller info when call is rejected
        this.callMembers = []; // Clear members info when call is rejected
        await this.incomingToast?.dismiss();
        console.log('Call rejected', event);
        await this.presentToast('Call rejected', 'success', 'bottom');
        await this.stopWaitingCallToast();
        this.cdr.detectChanges();
      } else if (event.state === 'ringing') {
        console.log('Call ringing2', event);
        // Only mark as incoming call if we don't already have an outgoing call in progress
        if (!this.isOutgoingCall) {
          console.log('Call ringing3', event);
          // This is an incoming call
          this.isIncomingCall = true;
          this.isOutgoingCall = false;
          this.incomingCallId = event.callId;
          
          // Extract caller information if available
          if (event.caller) {
            this.callerInfo = {
              userId: event.caller.userId,
              name: event.caller.name,
              imageURL: event.caller.imageURL
            };
          }
          
          console.log('Incoming call from:', this.callerInfo);
        } else {
          // This is an outgoing call that is now ringing - keep the outgoing state
          console.log('Outgoing call is now ringing');
        }
        this.cdr.detectChanges();
      } else if (event.state === 'created') {
        // Only set as outgoing call if it's not already marked as incoming AND there's no caller info
        // If there's caller info, this is an incoming call that someone else created
        if (!this.isIncomingCall && !event.caller) {
          console.log('Processing created event:', event);
          
          // For outgoing calls, we should be the one initiating, so if we get a 'created' 
          // event and we're not already in an incoming call state, it's likely our outgoing call
          this.isOutgoingCall = true;
          
          console.log('Event members (raw):', event.members);
          
          // Members should now be a proper array from all platforms
          if (event.members && Array.isArray(event.members)) {
            // Filter out self from the members list for outgoing calls
            const currentUserId = this.getCurrentUserId();
            console.log('Current user ID for filtering:', currentUserId);
            console.log('Event members before filtering:', event.members);
            
            this.callMembers = event.members
              .filter((member: any) => {
                const shouldInclude = member.userId !== currentUserId;
                console.log(`Member ${member.userId}: shouldInclude=${shouldInclude} (current user: ${currentUserId})`);
                return shouldInclude;
              })
              .map((member: any) => ({
                userId: member.userId,
                name: member.name,
                imageURL: member.imageURL
              }));
            
            console.log('Final callMembers (filtered):', this.callMembers);
          } else {
            console.log('No valid members data');
            this.callMembers = [];
          }
          
          this.cdr.detectChanges();
        } else if (event.caller) {
          console.log('Created event has caller info - this is an incoming call created by someone else, ignoring for outgoing UI');
        }
      } else if (event.state === 'ended' && event.reason === 'all_rejected_or_missed' && Capacitor.getPlatform() === 'web') {
        await this.presentToast('Call rejected or missed by all participants', 'success');
        this.isInCall = false;
        this.isOutgoingCall = false;
        this.isIncomingCall = false;
        this.callerInfo = null;
        this.callMembers = [];
        this.cdr.detectChanges();
      } else if (event.state === 'camera_enabled' as any) {
        this.isCameraOff = false;
        console.log('📹 Camera enabled in call overlay');
        this.cdr.detectChanges();
      } else if (event.state === 'camera_disabled' as any) {
        this.isCameraOff = true;
        console.log('📹 Camera disabled in call overlay');
        this.cdr.detectChanges();
      } else if (event.state === 'microphone_enabled' as any) {
        this.isMuted = false;
        console.log('🎤 Microphone enabled in call overlay');
        this.cdr.detectChanges();
      } else if (event.state === 'microphone_disabled' as any) {
        this.isMuted = true;
        console.log('🎤 Microphone disabled in call overlay');
        this.cdr.detectChanges();
      } else {
        if (Capacitor.getPlatform() !== 'ios') {
          console.log('Call event', event);
          this.cdr.detectChanges();
        }
      }
    });

    // Android lock-screen full-screen intent
    if (Capacitor.getPlatform() === 'android') {
      StreamCall.addListener('incomingCall', async (payload: any) => {
        console.log('[incomingCall] lock-screen payload', JSON.stringify(payload));
        
        // Mark this as an incoming call to prevent conflicts
        this.isIncomingCall = true;
        this.isOutgoingCall = false;
        this.incomingCallId = payload.cid;
        
        // Extract caller information if available
        if (payload.caller) {
          this.callerInfo = {
            userId: payload.caller.userId,
            name: payload.caller.name,
            imageURL: payload.caller.imageURL
          };
          console.log('[incomingCall] callerInfo', JSON.stringify(this.callerInfo));
        }
        
        this.isLockscreenIncoming = true;
        console.log('[incomingCall] Android lock-screen incoming call from:', this.callerInfo);
        this.cdr.detectChanges();
      });
    }
  }
}
