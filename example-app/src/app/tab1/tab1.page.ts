import { Component } from '@angular/core';
import { CallType, CallOptions, StreamCall } from '@capgo/capacitor-stream-call';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AlertController, ToastController } from '@ionic/angular';
import { AppComponent } from '../app.component';

@Component({
  selector: 'app-tab1',
  templateUrl: 'tab1.page.html',
  styleUrls: ['tab1.page.scss'],
  standalone: false,
  host: {
    '[class.transparent]': 'transparent'
  }
})
export class Tab1Page {
  private readonly STYLE_ID = 'magic_transparent_background';
  private readonly API_URL = 'https://streamcall-78.localcan.dev';
  private readonly API_KEY = 'vq4zdsazqxd7';
  private readonly DEV_API_KEY = 'm9ueqnjut3qs'; // Replace with actual dev API key
  transparent = false;
  currentUser: {
    userId: string;
    name: string;
    imageURL: string;
    teams: string[];
  } | null = null;

  allUsers = [
    { userId: 'user1', name: 'User 1' },
    { userId: 'user2', name: 'User 2' },
    { userId: 'user3', name: 'User 3' },
    { userId: 'user4', name: 'User 4' },
    { userId: 'user5', name: 'User 5' },
    { userId: 'user6', name: 'User 6' },
    { userId: 'user7', name: 'User 7' },
    { userId: 'user8', name: 'User 8' },
    { userId: 'user_red_1', name: 'User Red 1', team: 'red' },
    { userId: 'user_red_2', name: 'User Red 2', team: 'red' },
    { userId: 'user_red_3', name: 'User Red 3', team: 'red' },
    { userId: 'user_blue_1', name: 'User Blue 1', team: 'blue' },
    { userId: 'user_blue_2', name: 'User Blue 2', team: 'blue' },
    { userId: 'user_blue_3', name: 'User Blue 3', team: 'blue' },
  ];

  callStatus: string = 'waiting for response from SDK';
  currentEnvironment: 'normal' | 'dev' = 'normal';
  environmentText: string = 'Loading...';
  ringUsers = true;
  callType: CallType = 'default';

  constructor(
    private http: HttpClient,
    private toastController: ToastController,
    private appComponent: AppComponent,
    private alertController: AlertController
  ) {
    void this.loadCurrentEnvironment();
    void this.loadStoredUser();
    this.getCallStatus();
  }

  private async loadCurrentEnvironment() {
    try {
      const result = await StreamCall.getDynamicStreamVideoApikey();
      if (result.hasDynamicKey) {
        this.currentEnvironment = 'dev';
        this.environmentText = 'Env: Dev (dynamic API key)';
      } else {
        this.currentEnvironment = 'normal';
        this.environmentText = 'Env: Normal (static API key)';
      }
    } catch (error) {
      console.error('Failed to get environment:', error);
      this.currentEnvironment = 'normal';
      this.environmentText = 'Env: Unknown (error status)';
    }
  }

  async switchEnvironment() {
    try {
      if (this.currentEnvironment === 'normal') {
        // Switch to dev environment
        await StreamCall.setDynamicStreamVideoApikey({ apiKey: this.DEV_API_KEY });
        this.currentEnvironment = 'dev';
        this.environmentText = 'Env: Dev (dynamic API key)';
        await this.presentToast('Switched to Dev environment', 'success');
              } else {
          // Switch to normal environment by setting an empty dynamic key
          // This will cause getEffectiveApiKey to fall back to the static key
          await StreamCall.setDynamicStreamVideoApikey({ apiKey: '' });
          this.currentEnvironment = 'normal';
          this.environmentText = 'Env: Normal (static API key)';
          await this.presentToast('Switched to Normal environment', 'success');
        }
      
      // If user is logged in, show message about re-login
      if (this.currentUser) {
        await this.presentToast('Please logout and login again to use the new environment', 'danger');
      }
    } catch (error) {
      console.error('Failed to switch environment:', error);
      await this.presentToast('Failed to switch environment', 'danger');
    }
  }

  private async getCallStatus() {
    StreamCall.addListener('callEvent', (event) => {
      console.log('callEvent', event);
      if (event.callId) {
        this.callStatus = JSON.stringify(event, null, 2);
      }
    });
    try {
      this.callStatus = JSON.stringify(await StreamCall.getCallStatus(), null, 2);
    } catch (error) {
      console.error('Failed to get call status:', error);
      this.callStatus = 'Failed to get call status';
    }
  }

  private async loadStoredUser() {
    const storedUser = localStorage.getItem('currentUser');
    if (storedUser) {
      this.currentUser = JSON.parse(storedUser);
      if (this.currentUser) {
        if (this.currentUser.teams && this.currentUser.teams.length > 0) {
          await this.login(this.currentUser.userId, this.currentUser.teams[0]);
        } else {
          await this.login(this.currentUser.userId);
        }
      }
    }
  }

  async login(userId: string, team?: string) {
    try {
      const response = await firstValueFrom(this.http.get<{
        token: string;
        userId: string;
        name: string;
        imageURL: string;
        teams: string[];
      }>(`${this.API_URL}/user?user_id=${userId}${!!team ? `&team=${team}` : ''}${this.currentEnvironment === 'dev' ? '&environment=dev' : ''}`));

      if (!response) {
        throw new Error('No response from server');
      }

      // Use appropriate API key based on current environment
      const apiKeyToUse = this.currentEnvironment === 'dev' ? this.DEV_API_KEY : this.API_KEY;

      await StreamCall.login({
        token: response.token,
        userId: response.userId,
        name: response.name,
        imageURL: response.imageURL,
        apiKey: apiKeyToUse,
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
        teams: response.teams,
      };
      localStorage.setItem('currentUser', JSON.stringify(this.currentUser));
      
      // Set current user ID in AppComponent for filtering
      this.appComponent.setCurrentUserId(response.userId);
      
      await this.presentToast(`Login successful (${this.currentEnvironment} environment)`, 'success');
      
    } catch (error) {
      console.error('Login failed:', error);
      await this.presentToast('Login failed', 'danger');
    }
  }

  async presentLoginModal(onLoginSuccess?: () => Promise<void>) {
    const inputs = this.allUsers.map(user => ({
      name: 'user',
      type: 'radio' as const,
      label: user.name,
      value: user.userId,
      checked: false,
    }));

    const alert = await this.alertController.create({
      header: 'Select User to Login',
      inputs,
      buttons: [
        {
          text: 'Cancel',
          role: 'cancel',
        },
        {
          text: 'Login',
          handler: async (userId) => {
            if (userId) {
              const user = this.allUsers.find(u => u.userId === userId);
              try {
                await this.login(userId, user?.team);
                if (this.currentUser && onLoginSuccess) {
                  await onLoginSuccess();
                }
              } catch (error) {
                  console.error('Login failed in modal:', error);
              }
            }
          },
        },
      ],
    });

    await alert.present();
  }
  
  async presentCallModal(isAudioOnly: boolean) {
    if (!this.currentUser) {
      this.presentToast('Please login first', 'danger');
      return;
    }

    const lastCalledUsersString = localStorage.getItem('lastCalledUsers');
    const lastCalledUsers: string[] = lastCalledUsersString ? JSON.parse(lastCalledUsersString) : [];

    const availableUsers = this.allUsers.filter(user => user.userId !== this.currentUser?.userId);
    
    availableUsers.sort((a, b) => {
      const aIsLastCalled = lastCalledUsers.includes(a.userId);
      const bIsLastCalled = lastCalledUsers.includes(b.userId);
      if (aIsLastCalled && !bIsLastCalled) {
        return -1;
      }
      if (!aIsLastCalled && bIsLastCalled) {
        return 1;
      }
      return a.name.localeCompare(b.name);
    });

    const userInputs = availableUsers.map(user => ({
      name: 'userIds',
      type: 'checkbox' as const,
      label: user.name,
      value: user.userId,
      checked: lastCalledUsers.includes(user.userId),
    }));

    const alert = await this.alertController.create({
      header: isAudioOnly ? 'New Audio Call' : 'New Video Call',
      inputs: [
        ...userInputs
      ],
      buttons: [
        {
          text: 'Cancel',
          role: 'cancel',
        },
        {
          text: 'Call',
          handler: (data) => {
            const userIds = Array.isArray(data) ? data : (data ? [data] : []);

            if (userIds.length === 0) {
              this.presentToast('You must select at least one user to call.', 'danger');
              return false; // Prevent dismiss
            }

        
            const options: CallOptions = {
                userIds,
                type: this.callType,
                ring: this.ringUsers,
            };
        
            if (isAudioOnly) {
                options.custom = {
                    ...(options.custom || {}),
                    audio_only: true,
                };
            } else {
                options.video = true;
            }
        
            this.makeCall(options);
            return true;
          },
        },
      ],
    });

    await alert.present();
  }

  async makeCall(options: CallOptions) {
    try {
      await StreamCall.call(options);
      localStorage.setItem('lastCalledUsers', JSON.stringify(options.userIds));
    } catch (error) {
        console.error(`Failed to call with options ${JSON.stringify(options)}:`, error);
        await this.presentToast(`Failed to make call`, 'danger');
    }
  }

  async presentTeamCallModal(isAudioOnly: boolean) {
    if (!this.currentUser) {
      await this.presentLoginModal(async () => {
        await this.showTeamSelectionModal(isAudioOnly);
      });
    } else {
      await this.showTeamSelectionModal(isAudioOnly);
    }
  }

  async showTeamSelectionModal(isAudioOnly: boolean) {
    const allTeams = [...new Set(this.allUsers.filter(u => u.team).map(u => u.team!))];

    if (allTeams.length === 0) {
        this.presentToast('No teams available to call.', 'danger');
        return;
    }

    const teamInputs = allTeams.map(team => ({
      name: 'team',
      type: 'radio' as const,
      label: team,
      value: team,
      checked: false,
    }));
    teamInputs[0].checked = true;

    const alert = await this.alertController.create({
      header: 'Select Team to Call',
      inputs: teamInputs,
      buttons: [
        {
          text: 'Cancel',
          role: 'cancel',
        },
        {
          text: 'Call',
          handler: (team) => {
            if (!team) {
              this.presentToast('You must select a team.', 'danger');
              return false;
            }
            this.makeTeamCall(team, isAudioOnly);
            return true;
          },
        },
      ],
    });

    await alert.present();
  }

  makeTeamCall(team: string, isAudioOnly: boolean) {
    const teamMembers = this.allUsers.filter(user => user.team === team && user.userId !== this.currentUser?.userId);

    if (teamMembers.length === 0) {
      this.presentToast(`No other users found in team "${team}".`, 'danger');
      return;
    }

    const userIds = teamMembers.map(user => user.userId);

    const options: CallOptions = {
        userIds,
        type: this.callType,
        ring: this.ringUsers,
    };

    if (isAudioOnly) {
        options.custom = {
            ...(options.custom || {}),
            audio_only: true,
        };
    } else {
        options.video = true;
    }

    this.makeCall(options);
  }

  async logout() {
    try {
      await StreamCall.logout();
      this.currentUser = null;
      localStorage.removeItem('currentUser');
      
      // Clear current user ID in AppComponent
      this.appComponent.setCurrentUserId('');
      
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
      position: 'bottom'
    });
    await toast.present();
  }

}
