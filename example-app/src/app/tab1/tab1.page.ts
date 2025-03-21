import { Component } from '@angular/core';
import { StreamCall } from '@capgo/capacitor-stream-call';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ToastController } from '@ionic/angular';

@Component({
  selector: 'app-tab1',
  templateUrl: 'tab1.page.html',
  styleUrls: ['tab1.page.scss'],
  standalone: false,
})
export class Tab1Page {
  private readonly STYLE_ID = 'magic_transparent_background';
  private readonly API_URL = 'https://magic-login-srvv2-21.localcan.dev';
  private readonly API_KEY = 'n8wv8vjmucdw';
  transparent = false;
  currentUser: {
    userId: string;
    name: string;
    imageURL: string;
  } | null = null;

  constructor(
    private http: HttpClient,
    private toastController: ToastController
  ) {
    void this.loadStoredUser();
  }

  private async loadStoredUser() {
    const storedUser = localStorage.getItem('currentUser');
    if (storedUser) {
      this.currentUser = JSON.parse(storedUser);
      if (this.currentUser) {
        await this.login(this.currentUser.userId);
      }
    }
  }

  async login(userId: string) {
    try {
      const response = await firstValueFrom(this.http.get<{
        token: string;
        userId: string;
        name: string;
        imageURL: string;
      }>(`${this.API_URL}/user?user_id=${userId}`));

      if (!response) {
        throw new Error('No response from server');
      }

      await StreamCall.login({
        token: response.token,
        userId: response.userId,
        name: response.name,
        imageURL: response.imageURL,
        apiKey: this.API_KEY,
        magicDivId: 'call-container',
        // refreshToken: {
        //   url: `${this.API_URL}/user?user_id=${userId}`,
        //   headers: {
        //     'Content-Type': 'application/json',
        //   },
        // },
      });

      this.currentUser = {
        userId: response.userId,
        name: response.name,
        imageURL: response.imageURL,
      };
      localStorage.setItem('currentUser', JSON.stringify(this.currentUser));
      await this.presentToast('Login successful', 'success');
      
    } catch (error) {
      console.error('Login failed:', error);
      await this.presentToast('Login failed', 'danger');
    }
  }

  async callUser(userIds: string[]) {
    try {
      await StreamCall.call({
        userIds: userIds,
        type: 'default',
        ring: true
      });
      await this.presentToast(`Calling ${userIds}...`, 'success');
    } catch (error) {
      console.error(`Failed to call ${userIds}:`, error);
      await this.presentToast(`Failed to call ${userIds}`, 'danger');
    }
  }

  async logout() {
    try {
      await StreamCall.logout();
      this.currentUser = null;
      localStorage.removeItem('currentUser');
      await this.presentToast('Logout successful', 'success');
    } catch (error) {
      console.error('Logout failed:', error);
      await this.presentToast('Logout failed', 'danger');
    }
  }

  closeTransparency() {
    const styleElement = document.getElementById(this.STYLE_ID);
    if (styleElement) {
      document.head.removeChild(styleElement);
    }
    this.transparent = false;
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

}
