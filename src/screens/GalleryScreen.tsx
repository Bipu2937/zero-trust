import React, {useCallback, useEffect, useRef, useState} from 'react';
import {FlatList, Pressable, StyleSheet, Text, View} from 'react-native';
import {ConfirmSheet} from '../components/ConfirmSheet';
import {SecureMediaView} from '../native/SecureMediaView';
import {
  onVaultActivity,
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
 * Vault contents, shown as a thumbnail grid. Crucially, the previews are
 * NOT JS-side bitmaps — that would mean plaintext bytes in the JS heap.
 * Each tile mounts a small SecureMediaView in `thumbnail` mode: the native
 * side decrypts and draws a down-sampled still (a poster frame for videos)
 * straight onto a secure surface. So the grid is screenshot-black and no
 * decrypted byte ever crosses the bridge, yet you can see what you're
 * about to open.
 *
 * Also the landing screen after every unlock, so it owns the banner that
 * reports queued import/export outcomes (those finish after re-unlock,
 * long after the screen that started them is gone).
 */
export function GalleryScreen({state, onOpen}: Props): React.JSX.Element {
  const [items, setItems] = useState<VaultItemMeta[]>([]);
  const [busy, setBusy] = useState(false);
  const [importSheet, setImportSheet] = useState(false);
  const [banner, setBanner] = useState<string | null>(null);
  const bannerTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const refresh = useCallback(() => {
    Vault.listItems()
      .then(setItems)
      .catch(() => setItems([]));
  }, []);

  const showBanner = useCallback((text: string) => {
    setBanner(text);
    if (bannerTimer.current) {
      clearTimeout(bannerTimer.current);
    }
    bannerTimer.current = setTimeout(() => setBanner(null), 5000);
  }, []);

  useEffect(() => {
    refresh();
    const unsubChanged = onVaultChanged(refresh);
    const unsubActivity = onVaultActivity(event => {
      if (event.type === 'export') {
        showBanner(event.success ? 'Export complete ✓' : 'Export failed');
      } else if (event.type === 'import') {
        const failed = event.failed ?? 0;
        showBanner(
          `Imported ${event.imported ?? 0} item(s)` +
            (failed > 0 ? `, ${failed} failed` : ' ✓'),
        );
      }
    });
    return () => {
      unsubChanged();
      unsubActivity();
      if (bannerTimer.current) {
        clearTimeout(bannerTimer.current);
      }
    };
  }, [refresh, showBanner]);

  const importMedia = (deleteOriginals: boolean) => {
    setImportSheet(false);
    setBusy(true);
    // The picker backgrounds the app → instant lock. The import is queued
    // natively and runs after the next unlock; outcome arrives via the
    // vaultActivity banner.
    Vault.importMedia(deleteOriginals)
      .then(refresh)
      .catch(() => undefined)
      .finally(() => setBusy(false));
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

      {banner && <Text style={styles.banner}>{banner}</Text>}

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
            <View style={styles.thumbFrame}>
              <SecureMediaView
                itemId={item.id}
                thumbnail
                style={styles.thumb}
              />
              {item.kind === 'video' && (
                <View style={styles.playBadge} pointerEvents="none">
                  <Text style={styles.playBadgeText}>▶</Text>
                </View>
              )}
            </View>
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
        onPress={() => setImportSheet(true)}>
        <Text style={styles.importButtonText}>
          {busy ? 'Working…' : '+ Import media'}
        </Text>
      </Pressable>

      {importSheet && (
        <ConfirmSheet
          title="Move to Vault"
          message="Pick photos or videos to encrypt into the vault. The vault locks while the picker is open; the import finishes right after you unlock again. Delete the originals afterwards?"
          actions={[
            {label: 'Keep originals', onPress: () => importMedia(false)},
            {
              label: 'Move (delete originals)',
              danger: true,
              onPress: () => importMedia(true),
            },
          ]}
          onCancel={() => setImportSheet(false)}
        />
      )}
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
  banner: {
    color: colors.background,
    backgroundColor: colors.accent,
    marginHorizontal: spacing.md,
    marginBottom: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: 8,
    fontWeight: '600',
    overflow: 'hidden',
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
    overflow: 'hidden',
  },
  thumbFrame: {
    width: '100%',
    aspectRatio: 1,
    backgroundColor: '#000',
    justifyContent: 'center',
    alignItems: 'center',
  },
  thumb: {
    ...StyleSheet.absoluteFillObject,
  },
  playBadge: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: 'rgba(11, 15, 20, 0.55)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  playBadgeText: {
    color: colors.text,
    fontSize: 14,
    marginLeft: 2,
  },
  tileName: {
    color: colors.text,
    fontSize: 14,
    fontWeight: '600',
    paddingHorizontal: spacing.md,
    marginTop: spacing.sm,
  },
  tileMeta: {
    color: colors.textDim,
    fontSize: 11,
    marginTop: 2,
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.md,
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
