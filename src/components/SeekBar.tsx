/**
 * Hand-rolled seek bar (dependency minimalism: no community slider).
 * Tracks touch with a PanResponder; reports the final position on
 * release so we don't spam the native player with seeks mid-drag.
 *
 * Position is computed from the touch's absolute screen X (pageX) minus
 * the track's own screen X — NOT from nativeEvent.locationX. locationX is
 * relative to whichever child view the finger actually landed on (the
 * fill, or the absolutely-positioned thumb), so using it made a tap jump
 * to the wrong spot whenever it hit the thumb or the filled region.
 */
import React, {useRef, useState} from 'react';
import {
  LayoutChangeEvent,
  PanResponder,
  StyleSheet,
  View,
} from 'react-native';
import {colors} from '../theme';

interface Props {
  positionMs: number;
  durationMs: number;
  onSeek: (positionMs: number) => void;
}

const THUMB = 14;

export function SeekBar({positionMs, durationMs, onSeek}: Props): React.JSX.Element {
  const [trackWidth, setTrackWidth] = useState(0);
  const [scrubRatio, setScrubRatio] = useState<number | null>(null);

  // Refs so the (once-created) PanResponder always sees fresh values.
  const containerRef = useRef<View>(null);
  const widthRef = useRef(0);
  // Track's left edge in screen coordinates; refreshed on every grant so
  // it stays correct after scrolls/rotations without a re-render.
  const pageXRef = useRef(0);
  const durationRef = useRef(durationMs);
  const onSeekRef = useRef(onSeek);
  const lastRatioRef = useRef(0);
  durationRef.current = durationMs;
  onSeekRef.current = onSeek;

  const ratioFromPageX = (pageX: number) => {
    const w = widthRef.current;
    if (w <= 0) {
      return 0;
    }
    return Math.min(1, Math.max(0, (pageX - pageXRef.current) / w));
  };

  const measure = () => {
    containerRef.current?.measureInWindow((x, _y, w) => {
      pageXRef.current = x;
      if (w > 0) {
        widthRef.current = w;
      }
    });
  };

  const pan = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: e => {
        // Re-measure up front: the absolute origin may have shifted since
        // layout (parent scroll, orientation change).
        measure();
        const ratio = ratioFromPageX(e.nativeEvent.pageX);
        lastRatioRef.current = ratio;
        setScrubRatio(ratio);
      },
      onPanResponderMove: e => {
        const ratio = ratioFromPageX(e.nativeEvent.pageX);
        lastRatioRef.current = ratio;
        setScrubRatio(ratio);
      },
      onPanResponderRelease: () => {
        onSeekRef.current(lastRatioRef.current * durationRef.current);
        setScrubRatio(null);
      },
      onPanResponderTerminate: () => setScrubRatio(null),
    }),
  ).current;

  const ratio =
    scrubRatio ?? (durationMs > 0 ? Math.min(1, positionMs / durationMs) : 0);

  const onLayout = (e: LayoutChangeEvent) => {
    widthRef.current = e.nativeEvent.layout.width;
    setTrackWidth(e.nativeEvent.layout.width);
    measure();
  };

  return (
    <View
      ref={containerRef}
      style={styles.touchArea}
      {...pan.panHandlers}
      onLayout={onLayout}>
      <View style={styles.track}>
        <View style={[styles.fill, {width: `${ratio * 100}%`}]} />
      </View>
      <View
        style={[
          styles.thumb,
          {left: Math.max(0, ratio * trackWidth - THUMB / 2)},
        ]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  touchArea: {
    height: 32,
    justifyContent: 'center',
  },
  track: {
    height: 4,
    borderRadius: 2,
    backgroundColor: colors.border,
    overflow: 'hidden',
  },
  fill: {
    height: '100%',
    backgroundColor: colors.accent,
  },
  thumb: {
    position: 'absolute',
    width: THUMB,
    height: THUMB,
    borderRadius: THUMB / 2,
    backgroundColor: colors.accent,
  },
});
