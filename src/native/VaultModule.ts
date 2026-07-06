/**
 * Typed facade over the Kotlin VaultModule.
 *
 * Zero-trust contract: everything crossing this bridge is either a
 * command or metadata. There is deliberately NO API that could return
 * key material or media bytes — such a function does not exist natively.
 */
import {NativeEventEmitter, NativeModules} from 'react-native';

export interface VaultState {
  pinSet: boolean;
  unlocked: boolean;
  /** Device has a secure lock screen → KEK is user-auth-bound. */
  deviceSecure: boolean;
  /** KEK lives in a StrongBox secure element (vs TEE). */
  strongBox: boolean;
}

export interface VaultItemMeta {
  id: string;
  name: string;
  mime: string;
  kind: 'image' | 'video';
  size: number;
  createdAt: number;
}

export interface ImportResult {
  imported: number;
  failed: number;
}

export interface VaultActivityEvent {
  type: 'import' | 'export';
  success?: boolean;
  imported?: number;
  failed?: number;
}

interface NativeVaultModule {
  getState(): Promise<VaultState>;
  lock(): void;
  wipeVault(): Promise<boolean>;
  listItems(): Promise<VaultItemMeta[]>;
  deleteItem(itemId: string): Promise<boolean>;
  importMedia(deleteOriginals: boolean): Promise<ImportResult>;
  exportItem(itemId: string, name: string, mime: string): Promise<boolean>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

const native = NativeModules.VaultModule as NativeVaultModule;

if (!native) {
  throw new Error(
    'VaultModule native module missing — was the Android app rebuilt?',
  );
}

export const Vault = native;

const emitter = new NativeEventEmitter(NativeModules.VaultModule);

/** Fires with `true`/`false` when the native session unlocks/locks. */
export function onLockStateChanged(
  listener: (unlocked: boolean) => void,
): () => void {
  const sub = emitter.addListener(
    'vaultLockState',
    (event: {unlocked: boolean}) => listener(event.unlocked),
  );
  return () => sub.remove();
}

/** Fires when vault contents changed (e.g. an import finished). */
export function onVaultChanged(listener: () => void): () => void {
  const sub = emitter.addListener('vaultChanged', listener);
  return () => sub.remove();
}

/**
 * Fires when a queued import/export finishes. These complete AFTER a
 * re-unlock (the file picker backgrounds the app, which instant-locks),
 * so the screen that started them is long unmounted — whatever is
 * mounted now uses this to show the outcome.
 */
export function onVaultActivity(
  listener: (event: VaultActivityEvent) => void,
): () => void {
  const sub = emitter.addListener('vaultActivity', listener);
  return () => sub.remove();
}
