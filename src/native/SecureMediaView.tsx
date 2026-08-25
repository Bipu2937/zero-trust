/**
 * Hardware-protected media surface. JS passes an opaque item id; the
 * Kotlin side decrypts and renders straight onto a secure (DRM-style)
 * SurfaceView. Screen captures of this view come back black, and the
 * decrypted bytes never exist in the JS runtime.
 *
 * The bridge stays command-and-metadata only: `seek(ms)` sends a
 * position, `progress` events return positions — never bytes.
 */
import React, {
  forwardRef,
  useImperativeHandle,
  useRef,
} from 'react';
import {
  findNodeHandle,
  requireNativeComponent,
  UIManager,
  ViewStyle,
} from 'react-native';

export interface MediaEvent {
  type: 'loaded' | 'ended' | 'error' | 'progress';
  message?: string;
  durationMs?: number;
  positionMs?: number;
}

interface NativeSecureMediaProps {
  itemId: string | null;
  paused: boolean;
  thumbnail?: boolean;
  onMediaEvent?: (event: {nativeEvent: MediaEvent}) => void;
  style?: ViewStyle;
}

const NativeSecureMedia =
  requireNativeComponent<NativeSecureMediaProps>('ZTVSecureMediaView');

export interface SecureMediaViewHandle {
  /** Seek video playback to a position in milliseconds. */
  seek(positionMs: number): void;
}

interface Props {
  itemId: string | null;
  paused?: boolean;
  /**
   * Render a single still poster (gallery-tile mode) instead of a live,
   * playable surface. Videos decode one frame; images look the same.
   */
  thumbnail?: boolean;
  onEvent?: (event: MediaEvent) => void;
  style?: ViewStyle;
}

export const SecureMediaView = forwardRef<SecureMediaViewHandle, Props>(
  function SecureMediaView(
    {itemId, paused = false, thumbnail = false, onEvent, style},
    ref,
  ): React.JSX.Element {
    const nativeRef = useRef<React.Component<NativeSecureMediaProps> | null>(
      null,
    );

    useImperativeHandle(ref, () => ({
      seek(positionMs: number) {
        const node = findNodeHandle(nativeRef.current);
        if (node != null) {
          UIManager.dispatchViewManagerCommand(node, 'seek', [
            Math.max(0, Math.round(positionMs)),
          ]);
        }
      },
    }));

    return (
      <NativeSecureMedia
        ref={nativeRef as never}
        itemId={itemId}
        paused={paused}
        thumbnail={thumbnail}
        style={style}
        onMediaEvent={e => onEvent?.(e.nativeEvent)}
      />
    );
  },
);
