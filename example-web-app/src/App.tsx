import {
  Call,
  CallControls,
  CallingState,
  SpeakerLayout,
  StreamCall,
  StreamTheme,
  StreamVideo,
  StreamVideoClient,
  useCallStateHooks,
  useCalls,
  RingingCall,
  CallRingEvent,
} from '@stream-io/video-react-sdk';

import './style.css';
import { useEffect, useState } from 'react';

export default function App() {
  const apiKey = 'n8wv8vjmucdw'//'mmhfdzb5evj2' //'n8wv8vjmucdw';
  const API_URL = 'https://magic-login-srv-35.localcan.dev';
  const [isCallActive, setIsCallActive] = useState(false);
  const [client, setClient] = useState<StreamVideoClient | null>(null);
  const [call, setCall] = useState<Call | null>(null);

  async function initializeClient() {
    if (!client) {
      // Fetch user credentials from backend user2
      const response = await fetch(`${API_URL}/user?user_id=user2`);
      const userData = await response.json();
      
      if (!userData) {
        throw new Error('No response from server');
      }
  
      // Create a new client instance with the fetched credentials
      const newClient = new StreamVideoClient({ 
        apiKey, 
        token: userData.token,
        user: {
          id: userData.userId,
          name: userData.name,
          image: userData.imageURL
        }
      });
      // const newClient = new StreamVideoClient({
      //   apiKey,
      //   token: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyX2lkIjoiYW5kcm9pZC10dXRvcmlhbC0zIn0.g5h8coX8J1XUNHagPFoGBI0D7bN6P0w2Sd2rui89puE",
      //   user: {
      //     id: "android-tutorial-3",
      //     name: "User 3", 
      //     image: "https://getstream.io/chat/docs/sdk/avatars/jpg/Bernard%20Windler.jpg"
      //   }
      // });
  
      // Store client in state
      setClient(newClient);
    }
  }

  // Initialize client when component mounts
  useEffect(() => {
    initializeClient();
  }, []); // Empty dependency array means this runs once on mount

  // Listen for incoming calls
  // useEffect(() => {
  //   if (!client) return;

  //   const handleCallRing = async (event: { type: "call.ring" } & CallRingEvent) => {
  //     if (!call) {
  //       const incomingCall = client.call('default', event.call.id);
  //       await incomingCall.get()
  //       setCall(incomingCall);
  //     }
  //   };

  //   client.on('call.ring', handleCallRing);

  //   return () => {
  //     client.off('call.ring', handleCallRing);
  //   };
  // }, [client, call]);

  // Watch for call state changes
  // useEffect(() => {
  //   if (!call) return;

  //   if (call.state.callingState === CallingState.JOINED) {
  //     setIsCallActive(true);
  //   } else if (call.state.callingState === CallingState.OFFLINE) {
  //     setCall(null);
  //     setIsCallActive(false);
  //   }
  // }, [call, call?.state.callingState]);

  async function joinCall() {  
    if (!call && client) {
      // Create and store call in state
      const uuid = Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15); // Generate random UUID using Math.random()
      const newCall = client.call('default', uuid);

      await newCall.getOrCreate({
        ring: true,
        data: {
          settings_override: {
            ring: {
              auto_cancel_timeout_ms: 30000,
              incoming_call_timeout_ms: 30000
            }
          },
          members: [
            // { user_id: 'android-tutorial-3' },
            // { user_id: 'android-tutorial-1' },
            { user_id: 'user2' },
            { user_id: 'user1' },
          ]
        }
      });
      setCall(newCall);
      setIsCallActive(true);
      return;
    }
  }


  const RingingCallsComponent = () => {
    const calls = useCalls();
    return (
      <>
        <StreamTheme>
          {calls.map((call) => (
            <StreamCall call={call} key={call.cid}>
              <RingingCall />
            </StreamCall>
          ))}
        </StreamTheme>
      </>
    )
  }

  return (
    <>
      {client && (
        <StreamVideo client={client}>
          {isCallActive && call ? (
            <StreamCall call={call}>
              <MyUILayout />
            </StreamCall>
          ) : (
            <> 
              <RingingCallsComponent />
              <button onClick={() => joinCall()}>Join Call</button>
            </>
          )}
        </StreamVideo>
      )}
    </>
  );

  // return <>
  //   {client && (
  //     <StreamVideo client={client}>
  //       <RingingCallsComponent />
  //     </StreamVideo>
  //   )}
  // </>
}

export const MyUILayout = () => {
  const { useCallCallingState } = useCallStateHooks();
  const callingState = useCallCallingState();

  if (callingState !== CallingState.JOINED) {
    return <div>Loading...</div>;
  }

  return (
    <StreamTheme>
      <SpeakerLayout participantsBarPosition='bottom' />
      <CallControls />
    </StreamTheme>
  );
};