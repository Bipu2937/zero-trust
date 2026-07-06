import React, {useCallback, useEffect, useState} from 'react';
import {
  Alert,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  onVaultChanged,
  Vault,
  VaultItemMeta,
  VaultState,
} from '../native/VaultModule';
import {colors, spacing} from '../theme';

interface Props {
  state: VaultState;
  onOpen: (items: VaultItemMeta[], index: number) => void;
}

function formatSize(bytes: number): string {
  if (bytes >= 1 << 30) {
    return `${(bytes / (1 << 30)).toFixed(1)} GB`;
  }
  if (bytes >= 1 << 20) {
    return `${(bytes / (1 << 20)).toFixed(1)} MB`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/**
 * Vault contents. Deliberately renders metadata only — names, sizes,
 * type glyphs. Pixel data appears exclusively inside the secure viewer
 * surface; there are no JS-side thumbnails because thumbnails would mean
 * plaintext bytes in the JS heap.
 */
export function GalleryScreen({state, onOpen}: Props): React.JSX.Element {
  const [items, setItems] = useState<VaultItemMeta[]>([]);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => {
    Vault.listItems()
      .then(setItems)
      .catch(() => setItems([]));
  }, []);

  useEffect(() => {
    refresh();
    return onVaultChanged(refresh);
  }, [refresh]);

  const importMedia = (deleteOriginals: boolean) => {
    setBusy(true);
    Vault.importMedia(deleteOriginals)
      .then(result => {
        if (result.failed > 0) {
          Alert.alert('Import', `${result.imported} added, ${result.failed} failed.`);
        }
        refresh();
      })
      .catch(() => undefined)
      .finally(() => setBusy(false));
  };

  const onImport = () => {
    Alert.alert('Move to Vault', 'Delete originals after encrypting?', [
      {text: 'Cancel', style: 'cancel'},
      {text: 'Keep originals', onPress: () => importMedia(false)},
      {
        text: 'Move (delete originals)',
        style: 'destructive',
        onPress: () => importMedia(true),
      },
    ]);
  };

  return (
    <View style={styles.container}>
      <View style={styles.topBar}>
        <View>
          <Text style={styles.title}>Vault</Text>
          <Text style={styles.subtitle}>
            {state.strongBox ? 'StrongBox' : 'TEE'} · air-gapped ·{' '}
            {items.length} item{items.length === 1 ? '' : 's'}
          </Text>
        </View>
        <Pressable
          style={styles.lockButton}
          onPress={() => Vault.lock()}
          accessibilityLabel="Lock vault now">
          <Text style={styles.lockButtonText}>LOCK</Text>
        </Pressable>
      </View>

      <FlatList
        data={items}
        keyExtractor={item => item.id}
        numColumns={2}
        columnWrapperStyle={styles.row}
        contentContainerStyle={styles.list}
        ListEmptyComponent={
          <Text style={styles.empty}>
            Nothing here yet. Import photos or videos — they are encrypted
            with AES-256-GCM under a hardware-backed key the moment they
            enter.
          </Text>
        }
        renderItem={({item, index}) => (
          <Pressable style={styles.tile} onPress={() => onOpen(items, index)}>
            <Text style={styles.tileGlyph}>
              {item.kind === 'video' ? '▶' : '◻'}
            </Text>
            <Text style={styles.tileName} numberOfLines={1}>
              {item.name}
            </Text>
            <Text style={styles.tileMeta}>
              {item.kind} · {formatSize(item.size)}
            </Text>
          </Pressable>
        )}
      />

      <Pressable
        style={[styles.importButton, busy && styles.importButtonBusy]}
        disabled={busy}
        onPress={onImport}>
        <Text style={styles.importButtonText}>
          {busy ? 'Working…' : '+ Import media'}
        </Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  topBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    paddingTop: spacing.xl,
    paddingBottom: spacing.md,
  },
  title: {
    color: colors.text,
    fontSize: 24,
    fontWeight: '700',
  },
  subtitle: {
    color: colors.textDim,
    fontSize: 12,
    marginTop: 2,
  },
  lockButton: {
    borderColor: colors.accent,
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  lockButtonText: {
    color: colors.accent,
    fontWeight: '700',
    fontSize: 12,
    letterSpacing: 1,
  },
  list: {
    paddingHorizontal: spacing.md,
    paddingBottom: 96,
    flexGrow: 1,
  },
  row: {
    gap: spacing.md,
    marginBottom: spacing.md,
  },
  tile: {
    flex: 1,
    backgroundColor: colors.surface,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.md,
    minHeight: 110,
    justifyContent: 'flex-end',
  },
  tileGlyph: {
    color: colors.accent,
    fontSize: 26,
    position: 'absolute',
    top: spacing.md,
    left: spacing.md,
  },
  tileName: {
    color: colors.text,
    fontSize: 14,
    fontWeight: '600',
  },
  tileMeta: {
    color: colors.textDim,
    fontSize: 11,
    marginTop: 2,
  },
  empty: {
    color: colors.textDim,
    textAlign: 'center',
    marginTop: spacing.xl * 2,
    paddingHorizontal: spacing.lg,
    lineHeight: 20,
  },
  importButton: {
    position: 'absolute',
    bottom: spacing.lg,
    left: spacing.md,
    right: spacing.md,
    backgroundColor: colors.accent,
    borderRadius: 12,
    alignItems: 'center',
    paddingVertical: spacing.md,
  },
  importButtonBusy: {
    opacity: 0.5,
  },
  importButtonText: {
    color: colors.background,
    fontWeight: '700',
    fontSize: 15,
  },
});
