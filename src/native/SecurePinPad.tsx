/**
 * The custom in-app keyboard. Rendering, touch handling and PIN
 * accumulation all happen inside the native PinPadView — this component
 * is only a placement shim. The typed digits never enter JavaScript;
 * JS receives only coarse outcome events.
 */
import React from 'react';
import {requireNativeComponent, ViewStyle} from 'react-native';

export type PinEventType =
  | 'unlocked'
  | 'wrong_pin'
  | 'lockout'
  | 'too_short'
  | 'confirm_stage'
  | 'mismatch'
  | 'auth_failed'
  | 'error';

export interface PinEvent {
  type: PinEventType;
  retryInMs?: number;
  message?: string;
}

interface NativePinPadProps {
  mode: 'verify' | 'setup';
  onPinEvent?: (event: {nativeEvent: PinEvent}) => void;
  style?: ViewStyle;
}

const NativePinPad = requireNativeComponent<NativePinPadProps>('ZTVPinPad');

interface Props {
  mode: 'verify' | 'setup';
  onEvent: (event: PinEvent) => void;
  style?: ViewStyle;
}

export function SecurePinPad({mode, onEvent, style}: Props): React.JSX.Element {
  return (
    <NativePinPad
      mode={mode}
      style={style}
      onPinEvent={e => onEvent(e.nativeEvent)}
    />
  );
}
