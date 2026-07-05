/**
 * JS-side mirror of the native instant-lock.
 *
 * The authoritative lock lives in Kotlin (MainActivity.onPause →
 * SessionManager.lock() zeroes the key material with no grace period).
 * This hook does two complementary jobs:
 *   1. subscribes to native lock-state events so the UI snaps to the
 *      lock screen the instant the session dies;
 *   2. redundantly calls Vault.lock() from AppState as defense in depth —
 *      if either layer is somehow bypassed, the other still locks.
 */
import {useEffect, useState} from 'react';
import {AppState} from 'react-native';
import {onLockStateChanged, Vault} from '../native/VaultModule';

export function useInstantLock(): {
  unlocked: boolean;
  setUnlocked: (value: boolean) => void;
} {
  const [unlocked, setUnlocked] = useState(false);

  useEffect(() => {
    const unsubscribe = onLockStateChanged(setUnlocked);

    const sub = AppState.addEventListener('change', state => {
      if (state !== 'active') {
        Vault.lock();
        setUnlocked(false);
      }
    });

    // Blur fires on Android for focus loss that doesn't change AppState.
    const blurSub = AppState.addEventListener('blur', () => {
      Vault.lock();
      setUnlocked(false);
    });

    return () => {
      unsubscribe();
      sub.remove();
      blurSub.remove();
    };
  }, []);

  return {unlocked, setUnlocked};
}
