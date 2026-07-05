import React, {useEffect, useState} from 'react';
import {StatusBar, StyleSheet, View} from 'react-native';
import {Vault, VaultItemMeta, VaultState} from './native/VaultModule';
import {GalleryScreen} from './screens/GalleryScreen';
import {LockScreen} from './screens/LockScreen';
import {ViewerScreen} from './screens/ViewerScreen';
import {useInstantLock} from './security/useInstantLock';
import {colors} from './theme';

/**
 * Root component. State machine is intentionally tiny:
 *
 *   locked (verify | first-run setup)  →  gallery  →  viewer
 *          ▲──────────── instant lock on any focus loss ─────────┘
 *
 * There is no navigation library (dependency minimalism) and no screen
 * is ever mounted while the native session is locked, so locked-state UI
 * can never leak vault contents.
 */
function App(): React.JSX.Element {
  const {unlocked} = useInstantLock();
  const [state, setState] = useState<VaultState | null>(null);
  const [viewing, setViewing] = useState<VaultItemMeta | null>(null);

  // Refresh persisted state whenever the lock state flips.
  useEffect(() => {
    Vault.getState().then(setState).catch(() => undefined);
    if (!unlocked) {
      setViewing(null);
    }
  }, [unlocked]);

  let screen: React.JSX.Element;
  if (!state) {
    screen = <View style={styles.container} />;
  } else if (!unlocked) {
    screen = (
      <LockScreen
        mode={state.pinSet ? 'verify' : 'setup'}
        deviceSecure={state.deviceSecure}
      />
    );
  } else if (viewing) {
    screen = <ViewerScreen item={viewing} onClose={() => setViewing(null)} />;
  } else {
    screen = <GalleryScreen state={state} onOpen={setViewing} />;
  }

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />
      {screen}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
});

export default App;
