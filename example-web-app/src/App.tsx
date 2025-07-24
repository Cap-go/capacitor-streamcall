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
  useCall,
} from '@stream-io/video-react-sdk';

import './style.css';
import { useEffect, useState } from 'react';
import { CustomRingingCall } from './CustomRingingCall';

export default function App() {
  const apiKey = 'n8wv8vjmucdw';
  const API_URL = 'https://streamcall-78.localcan.dev';
  const [isCallActive, setIsCallActive] = useState(false);
  const [callId, setCallId] = useState('');
  const [client, setClient] = useState<StreamVideoClient | null>(null);
  const [call, setCall] = useState<Call | null>(null);
  const [userCounter, setUserCounter] = useState(7);

  async function initializeClient() {
    // Fetch user credentials from backend with dynamic user ID
    const response = await fetch(`${API_URL}/user?user_id=user${userCounter}`);
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

    // If there's an existing client, disconnect it after creating the new one
    if (client) {
      await client.disconnectUser();
    }

    // Store client in state
    setClient(newClient);
  }

  // Initialize client when component mounts or counter changes
  useEffect(() => {
    initializeClient();
  }, [userCounter]); // Add userCounter to dependencies

  // Listen for incoming calls
  useEffect(() => {
    if (!client) return;

    const handleCallRing = async (event: { type: "call.ring" } & CallRingEvent) => {
      if (!call) {
        const incomingCall = client.call('default', event.call.id);
        await incomingCall.get()
        setCall(incomingCall);
      }
    };

    client.on('call.ring', handleCallRing);

    return () => {
      client.off('call.ring', handleCallRing);
    };
  }, [client, call]);

  // Watch for call state changes
  useEffect(() => {
    if (!call) return;

    if (call.state.callingState === CallingState.JOINED) {
      setIsCallActive(true);
    } else if (call.state.callingState === CallingState.OFFLINE) {
      setCall(null);
      setIsCallActive(false);
    }
  }, [call, call?.state.callingState]);

  async function joinCall() {  
    if (!call && client) {
      // Use provided callId or generate a random one
      const uuid = callId || Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
      const newCall = client.call('default', uuid);
      
      if (callId) {
        // If callId is provided, just join the existing call
        await newCall.join();
      } else {
        // If no callId, create a new call with ringing
        await newCall.getOrCreate({
          ring: true,
          data: {
            members: [
              { user_id: 'user3' },
              { user_id: 'user4' },
            ]
          }
        });
      }
      
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

  const MyMagicLayoutV2 = () => {
    const { useCallCallingState } = useCallStateHooks();
    const callingState = useCallCallingState();

    
    if (callingState === CallingState.JOINED) {
      return <MyUILayout />;
    } else if (
      [CallingState.RINGING, CallingState.JOINING].includes(callingState)
    ) {
      return <CustomRingingCall />;
    }
  
    return null;
  }

  const MyMagicLayout = () => {
    const calls = useCalls();
    return (
      <>
        {calls.map((call) => (
          <StreamCall call={call} key={call.cid}>
            <MyMagicLayoutV2 />
          </StreamCall>
        ))}
        <div style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '10px' }}>
          <input
            type="text"
            value={callId}
            onChange={(e) => setCallId(e.target.value)}
            placeholder="Enter call ID (optional)"
            style={{ padding: '8px', borderRadius: '4px', border: '1px solid #ccc' }}
          />
          <button 
            onClick={() => joinCall()}
            style={{ padding: '8px 16px', borderRadius: '4px', backgroundColor: '#007bff', color: 'white', border: 'none', cursor: 'pointer' }}
          >
            Join Call
          </button>
          <button 
            onClick={() => setUserCounter(prev => prev + 1)}
            style={{ padding: '8px 16px', borderRadius: '4px', backgroundColor: '#28a745', color: 'white', border: 'none', cursor: 'pointer' }}
          >
            Switch to User {userCounter + 1}
          </button>
          <span style={{ marginLeft: '10px' }}>Current User: user{userCounter}</span>
        </div>
      </>
    )
  }

  return (
    <>
      {client && (
        <StreamVideo client={client}>
          <StreamTheme>
            <MyMagicLayout />
          </StreamTheme>
          {/* {isCallActive && call ? (
            <StreamCall call={call}>
              <MyUILayout />
            </StreamCall>
          ) : (
            <> 
              <RingingCallsComponent />
              <button onClick={() => joinCall()}>Join Call</button>
            </>
          )} */}
          
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
