/**
 * Hardware-protected media surface. JS passes an opaque item id; the
 * Kotlin side decrypts and renders straight onto a secure (DRM-style)
 * SurfaceView. Screen captures of this view come back black, and the
 * decrypted bytes never exist in the JS runtime.
 */
import React from 'react';
import {requireNativeComponent, ViewStyle} from 'react-native';

export interface MediaEvent {
  type: 'loaded' | 'ended' | 'error';
  message?: string;
  durationMs?: number;
}

interface NativeSecureMediaProps {
  itemId: string | null;
  paused: boolean;
  onMediaEvent?: (event: {nativeEvent: MediaEvent}) => void;
  style?: ViewStyle;
}

const NativeSecureMedia =
  requireNativeComponent<NativeSecureMediaProps>('ZTVSecureMediaView');

interface Props {
  itemId: string | null;
  paused?: boolean;
  onEvent?: (event: MediaEvent) => void;
  style?: ViewStyle;
}

export function SecureMediaView({
  itemId,
  paused = false,
  onEvent,
  style,
}: Props): React.JSX.Element {
  return (
    <NativeSecureMedia
      itemId={itemId}
      paused={paused}
      style={style}
      onMediaEvent={e => onEvent?.(e.nativeEvent)}
    />
  );
}
