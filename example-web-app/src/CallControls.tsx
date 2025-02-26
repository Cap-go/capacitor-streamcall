import {
  AcceptCallButton,
  CallingState,
  CancelCallButton,
  IconButton,
  useCall,
  useCallStateHooks,
} from "@stream-io/video-react-sdk";

export const CallControls = () => {
  const call = useCall();
  const { useCallCallingState } = useCallStateHooks();
  const callingState = useCallCallingState();

  if (!call) return null;
  // show ringing call panel components only before the call is joined
  if (![CallingState.RINGING, CallingState.JOINING].includes(callingState))
    return null;

  // prevent users from rejecting call when already accepted
  const buttonsDisabled = callingState === CallingState.JOINING;

  return (
    <div>
      <ToggleAudioButton />
      <ToggleVideoButton />
      {call.isCreatedByMe ? (
        <CancelCallButton disabled={buttonsDisabled} />
      ) : (
        <>
          <AcceptCallButton disabled={buttonsDisabled} />
          <CancelCallButton
            onClick={() => {
              const reason = call.isCreatedByMe ? "cancel" : "decline";
              call.leave({ reject: true, reason });
            }}
            disabled={buttonsDisabled}
          />
        </>
      )}
    </div>
  );
};

const ToggleAudioButton = () => {
  const { useMicrophoneState } = useCallStateHooks();
  const { microphone, isMute } = useMicrophoneState();
  return (
    <IconButton
      icon={isMute ? "mic-off" : "mic"}
      onClick={() => microphone.toggle()}
    />
  );
};

const ToggleVideoButton = () => {
  const { useCameraState } = useCallStateHooks();
  const { camera, isMute } = useCameraState();
  return (
    <IconButton
      icon={isMute ? "camera-off" : "camera"}
      onClick={() => camera.toggle()}
    />
  );
};