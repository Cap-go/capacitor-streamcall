import { Component } from '@angular/core';
import { ChangeDetectorRef } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { ToastController } from '@ionic/angular';
import { StreamCall } from 'stream-call';

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
  incomingCallId: string | null = null;

  async endCall() {
    await StreamCall.endCall();
    this.isInCall = false;
    this.cdr.detectChanges();
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

  private async presentToast(message: string, color: 'success' | 'danger') {
    const toast = await this.toastController.create({
      message,
      duration: 2000,
      color,
      position: 'top'
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

  private async presentIncomingCallToast() {
    const toast = await this.toastController.create({
      message: 'Incoming call...',
      position: 'top',
      buttons: [
        {
          side: 'start',
          icon: 'call',
          handler: () => {
            void this.acceptCall();
          }
        },
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
    await toast.present();
  }

  ngOnInit() {
    console.log('Making app transparent and initializing StreamCall');
    StreamCall.removeAllListeners();
    
    // Add transparent background style
    const styleElement = document.createElement('style');
    styleElement.id = 'magic_transparent_background';
    styleElement.textContent = `
      :root {
        --ion-background-color: transparent !important;
      }
      ion-content {
        --background: transparent !important;
      }
      .ion-page {
        background: transparent !important;
      }
    `;
    document.head.appendChild(styleElement);

    // register event listeners
    StreamCall.addListener('callEvent', async(event) => {
      if (event.state === 'joined') {
        this.isInCall = true;
        console.log('Call started', event);
        this.cdr.detectChanges();
      } else if (event.state === 'left') {
        this.isInCall = false;
        console.log('Call ended', event);
        this.cdr.detectChanges();
      } else if (event.state === 'rejected') {
        this.isInCall = false;
        console.log('Call rejected', event);
        this.cdr.detectChanges();
      } else if (event.state === 'ringing') {
        this.incomingCallId = event.callId;
        await this.presentIncomingCallToast();
        this.cdr.detectChanges();
      }
    });
  }
}
