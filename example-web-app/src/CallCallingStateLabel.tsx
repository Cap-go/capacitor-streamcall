import {
  CallingState,
  useCallStateHooks,
  useI18n,
} from "@stream-io/video-react-sdk";

const CALLING_STATE_TO_LABEL: Record<CallingState, string> = {
  [CallingState.JOINING]: "Joining",
  [CallingState.RINGING]: "Ringing",
  [CallingState.RECONNECTING]: "Re-connecting",
  [CallingState.RECONNECTING_FAILED]: "Failed",
  [CallingState.OFFLINE]: "No internet connection",
  [CallingState.IDLE]: "",
  [CallingState.UNKNOWN]: "",
  [CallingState.JOINED]: "Joined",
  [CallingState.LEFT]: "Left call",
  [CallingState.MIGRATING]: "Migrating",
};

export const CallCallingStateLabel = () => {
  const { t } = useI18n();
  const { useCallCallingState } = useCallStateHooks();
  const callingState = useCallCallingState();
  const callingStateLabel = CALLING_STATE_TO_LABEL[callingState];

  return callingStateLabel ? <div>{t(callingStateLabel)}</div> : null;
};