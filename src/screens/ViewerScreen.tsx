import React, {useRef, useState} from 'react';
import {
  Alert,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import {SeekBar} from '../components/SeekBar';
import {
  MediaEvent,
  SecureMediaView,
  SecureMediaViewHandle,
} from '../native/SecureMediaView';
import {Vault, VaultItemMeta} from '../native/VaultModule';
import {colors, spacing} from '../theme';

interface Props {
  items: VaultItemMeta[];
  initialIndex: number;
  onClose: () => void;
}

function formatTime(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

/**
 * Full-screen pager over the vault. Swipe horizontally to slide between
 * items. Security invariant: only the CURRENTLY VISIBLE page mounts a
 * SecureMediaView, so exactly one decrypted surface exists at any time —
 * swiping away tears the old one down and wipes its buffers.
 */
export function ViewerScreen({items, initialIndex, onClose}: Props): React.JSX.Element {
  const {width} = useWindowDimensions();
  const [index, setIndex] = useState(
    Math.min(Math.max(initialIndex, 0), Math.max(items.length - 1, 0)),
  );

  const current = items[index];

  const onExport = () => {
    if (!current) {
      return;
    }
    Alert.alert(
      'Export from vault',
      `Decrypt "${current.name}" and write it to a location you choose?`,
      [
        {text: 'Cancel', style: 'cancel'},
        {
          text: 'Export',
          onPress: () => {
            Vault.exportItem(current.id).catch(() => Alert.alert('Export failed'));
          },
        },
      ],
    );
  };

  const onDelete = () => {
    if (!current) {
      return;
    }
    Alert.alert('Delete from vault', 'This shreds the encrypted file.', [
      {text: 'Cancel', style: 'cancel'},
      {
        text: 'Delete',
        style: 'destructive',
        onPress: () => {
          Vault.deleteItem(current.id)
            .then(onClose)
            .catch(() => Alert.alert('Delete failed'));
        },
      },
    ]);
  };

  return (
    <View style={styles.container}>
      <FlatList
        data={items}
        horizontal
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        keyExtractor={item => item.id}
        initialScrollIndex={index}
        getItemLayout={(_, i) => ({length: width, offset: width * i, index: i})}
        onMomentumScrollEnd={e => {
          const next = Math.round(e.nativeEvent.contentOffset.x / width);
          setIndex(Math.min(Math.max(next, 0), items.length - 1));
        }}
        renderItem={({item, index: i}) => (
          <MediaPage item={item} active={i === index} width={width} />
        )}
      />

      <View style={styles.bar}>
        <Pressable style={styles.barButton} onPress={onClose}>
          <Text style={styles.barButtonText}>‹ Back</Text>
        </Pressable>
        <Text style={styles.name} numberOfLines={1}>
          {current ? `${current.name}  ·  ${index + 1}/${items.length}` : ''}
        </Text>
        <Pressable style={styles.barButton} onPress={onExport}>
          <Text style={styles.barButtonText}>Export</Text>
        </Pressable>
        <Pressable style={styles.barButton} onPress={onDelete}>
          <Text style={[styles.barButtonText, styles.danger]}>Delete</Text>
        </Pressable>
      </View>
    </View>
  );
}

/**
 * One page of the pager. Inactive pages render plain black — no surface,
 * no decrypted data. Video pages get a control bar: play/pause, seek,
 * elapsed/total time (all driven by native progress events; positions
 * only, never media bytes).
 */
function MediaPage({
  item,
  active,
  width,
}: {
  item: VaultItemMeta;
  active: boolean;
  width: number;
}): React.JSX.Element {
  const mediaRef = useRef<SecureMediaViewHandle>(null);
  const [paused, setPaused] = useState(false);
  const [ended, setEnded] = useState(false);
  const [positionMs, setPositionMs] = useState(0);
  const [durationMs, setDurationMs] = useState(0);
  const [error, setError] = useState<string | null>(null);

  if (!active) {
    return <View style={[styles.page, {width}]} />;
  }

  const onEvent = (event: MediaEvent) => {
    switch (event.type) {
      case 'loaded':
        setDurationMs(event.durationMs ?? 0);
        break;
      case 'progress':
        setPositionMs(event.positionMs ?? 0);
        if (event.durationMs) {
          setDurationMs(event.durationMs);
        }
        break;
      case 'ended':
        setEnded(true);
        setPaused(true);
        setPositionMs(durationMs);
        break;
      case 'error':
        setError(event.message ?? 'Failed to display media');
        break;
    }
  };

  const togglePlay = () => {
    if (ended) {
      mediaRef.current?.seek(0);
      setPositionMs(0);
      setEnded(false);
      setPaused(false);
      return;
    }
    setPaused(p => !p);
  };

  const onSeek = (ms: number) => {
    mediaRef.current?.seek(ms);
    setPositionMs(ms);
    if (ended && ms < durationMs) {
      setEnded(false);
    }
  };

  return (
    <View style={[styles.page, {width}]}>
      <SecureMediaView
        ref={mediaRef}
        itemId={item.id}
        paused={paused}
        onEvent={onEvent}
        style={styles.media}
      />

      {error && <Text style={styles.status}>{error}</Text>}

      {item.kind === 'video' && (
        <View style={styles.controls}>
          <Pressable style={styles.playButton} onPress={togglePlay}>
            <Text style={styles.playButtonText}>
              {paused || ended ? '▶' : '❚❚'}
            </Text>
          </Pressable>
          <Text style={styles.time}>{formatTime(positionMs)}</Text>
          <View style={styles.seekBar}>
            <SeekBar
              positionMs={positionMs}
              durationMs={durationMs}
              onSeek={onSeek}
            />
          </View>
          <Text style={styles.time}>{formatTime(durationMs)}</Text>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  page: {
    flex: 1,
    backgroundColor: '#000',
  },
  media: {
    flex: 1,
  },
  status: {
    color: colors.danger,
    textAlign: 'center',
    padding: spacing.sm,
  },
  controls: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    backgroundColor: 'rgba(11, 15, 20, 0.85)',
    gap: spacing.sm,
  },
  playButton: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: colors.surfaceRaised,
    alignItems: 'center',
    justifyContent: 'center',
  },
  playButtonText: {
    color: colors.accent,
    fontSize: 14,
    fontWeight: '700',
  },
  time: {
    color: colors.textDim,
    fontSize: 12,
    fontVariant: ['tabular-nums'],
    minWidth: 40,
    textAlign: 'center',
  },
  seekBar: {
    flex: 1,
  },
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surface,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.md,
    gap: spacing.sm,
  },
  barButton: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
  },
  barButtonText: {
    color: colors.accent,
    fontWeight: '600',
  },
  danger: {
    color: colors.danger,
  },
  name: {
    flex: 1,
    color: colors.textDim,
    fontSize: 12,
    textAlign: 'center',
  },
});
