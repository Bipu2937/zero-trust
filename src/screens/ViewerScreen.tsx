import React, {useState} from 'react';
import {Alert, Pressable, StyleSheet, Text, View} from 'react-native';
import {MediaEvent, SecureMediaView} from '../native/SecureMediaView';
import {Vault, VaultItemMeta} from '../native/VaultModule';
import {colors, spacing} from '../theme';

interface Props {
  item: VaultItemMeta;
  onClose: () => void;
}

/**
 * Full-screen viewer. The visible pixels live on a hardware-secure
 * surface rendered entirely by Kotlin — any screenshot/recording/spyware
 * capture of this screen shows the chrome below with a black rectangle
 * where the media is.
 */
export function ViewerScreen({item, onClose}: Props): React.JSX.Element {
  const [paused, setPaused] = useState(false);
  const [status, setStatus] = useState<string | null>(null);

  const onEvent = (event: MediaEvent) => {
    if (event.type === 'error') {
      setStatus(event.message ?? 'Failed to display media');
    } else if (event.type === 'ended') {
      setPaused(true);
    }
  };

  const onExport = () => {
    Alert.alert(
      'Export from vault',
      `Decrypt "${item.name}" and write it to a location you choose?`,
      [
        {text: 'Cancel', style: 'cancel'},
        {
          text: 'Export',
          onPress: () => {
            Vault.exportItem(item.id).catch(() =>
              Alert.alert('Export failed'),
            );
          },
        },
      ],
    );
  };

  const onDelete = () => {
    Alert.alert('Delete from vault', 'This shreds the encrypted file.', [
      {text: 'Cancel', style: 'cancel'},
      {
        text: 'Delete',
        style: 'destructive',
        onPress: () => {
          Vault.deleteItem(item.id)
            .then(onClose)
            .catch(() => Alert.alert('Delete failed'));
        },
      },
    ]);
  };

  return (
    <View style={styles.container}>
      <Pressable
        style={styles.media}
        onPress={() => item.kind === 'video' && setPaused(p => !p)}>
        <SecureMediaView
          itemId={item.id}
          paused={paused}
          onEvent={onEvent}
          style={styles.media}
        />
      </Pressable>

      {status && <Text style={styles.status}>{status}</Text>}

      <View style={styles.bar}>
        <Pressable style={styles.barButton} onPress={onClose}>
          <Text style={styles.barButtonText}>‹ Back</Text>
        </Pressable>
        <Text style={styles.name} numberOfLines={1}>
          {item.name}
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

const styles = StyleSheet.create({
  container: {
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
