import React, {useEffect, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {PinEvent, SecurePinPad} from '../native/SecurePinPad';
import {colors, spacing} from '../theme';

interface Props {
  mode: 'verify' | 'setup';
  deviceSecure: boolean;
}

/**
 * Lock screen hosting the native PIN pad. Note what is absent: there is
 * no TextInput anywhere, so the system IME can never appear, and this
 * component never sees a digit — only outcome events.
 */
export function LockScreen({mode, deviceSecure}: Props): React.JSX.Element {
  const [status, setStatus] = useState<string>(
    mode === 'setup' ? 'Choose a PIN (4–8 digits)' : 'Enter PIN',
  );
  const [lockoutMs, setLockoutMs] = useState(0);

  // Live countdown while throttled.
  useEffect(() => {
    if (lockoutMs <= 0) {
      return;
    }
    const timer = setInterval(() => {
      setLockoutMs(ms => (ms > 1000 ? ms - 1000 : 0));
    }, 1000);
    return () => clearInterval(timer);
  }, [lockoutMs > 0]);

  const onEvent = (event: PinEvent) => {
    switch (event.type) {
      case 'unlocked':
        // App-level state flips via the native vaultLockState event.
        break;
      case 'confirm_stage':
        setStatus('Re-enter PIN to confirm');
        break;
      case 'mismatch':
        setStatus('PINs did not match — start over');
        break;
      case 'too_short':
        setStatus('PIN must be at least 4 digits');
        break;
      case 'wrong_pin':
        if (event.retryInMs && event.retryInMs > 0) {
          setLockoutMs(event.retryInMs);
          setStatus('Wrong PIN');
        } else {
          setStatus('Wrong PIN — try again');
        }
        break;
      case 'lockout':
        setLockoutMs(event.retryInMs ?? 0);
        break;
      case 'auth_failed':
        setStatus(event.message ?? 'Authentication failed');
        break;
      case 'error':
        setStatus(event.message ?? 'Error');
        break;
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Vault</Text>
        <Text style={styles.status}>
          {lockoutMs > 0
            ? `Locked out — try again in ${Math.ceil(lockoutMs / 1000)}s`
            : status}
        </Text>
        {!deviceSecure && (
          <Text style={styles.warning}>
            No device lock screen detected: hardware auth-binding is
            unavailable. Set a device PIN/biometric for full protection.
          </Text>
        )}
      </View>
      <SecurePinPad mode={mode} onEvent={onEvent} style={styles.pinPad} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  header: {
    alignItems: 'center',
    paddingTop: spacing.xl * 2,
    paddingHorizontal: spacing.lg,
  },
  title: {
    color: colors.text,
    fontSize: 28,
    fontWeight: '700',
    letterSpacing: 1,
  },
  status: {
    color: colors.textDim,
    fontSize: 15,
    marginTop: spacing.md,
  },
  warning: {
    color: colors.danger,
    fontSize: 12,
    marginTop: spacing.md,
    textAlign: 'center',
  },
  pinPad: {
    flex: 1,
    marginTop: spacing.lg,
    marginBottom: spacing.xl,
  },
});
