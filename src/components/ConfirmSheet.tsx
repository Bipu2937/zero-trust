/**
 * In-app confirmation sheet. Deliberately NOT React Native's Alert or
 * Modal: those create a separate Android window, which steals focus from
 * the main window and trips the instant lock (onWindowFocusChanged →
 * SessionManager.lock). This is a plain View overlay inside the same
 * window, so confirming an action never locks the vault.
 */
import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {colors, spacing} from '../theme';

export interface ConfirmAction {
  label: string;
  danger?: boolean;
  onPress: () => void;
}

interface Props {
  title: string;
  message?: string;
  actions: ConfirmAction[];
  onCancel: () => void;
}

export function ConfirmSheet({
  title,
  message,
  actions,
  onCancel,
}: Props): React.JSX.Element {
  return (
    <View style={styles.overlay}>
      <Pressable style={styles.backdrop} onPress={onCancel} />
      <View style={styles.card}>
        <Text style={styles.title}>{title}</Text>
        {message ? <Text style={styles.message}>{message}</Text> : null}
        {actions.map(action => (
          <Pressable
            key={action.label}
            style={[styles.button, action.danger && styles.buttonDanger]}
            onPress={action.onPress}>
            <Text
              style={[
                styles.buttonText,
                action.danger && styles.buttonTextDanger,
              ]}>
              {action.label}
            </Text>
          </Pressable>
        ))}
        <Pressable style={[styles.button, styles.cancel]} onPress={onCancel}>
          <Text style={styles.cancelText}>Cancel</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'flex-end',
    zIndex: 10,
  },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0, 0, 0, 0.6)',
  },
  card: {
    backgroundColor: colors.surfaceRaised,
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    padding: spacing.lg,
    gap: spacing.sm,
  },
  title: {
    color: colors.text,
    fontSize: 17,
    fontWeight: '700',
  },
  message: {
    color: colors.textDim,
    fontSize: 13,
    lineHeight: 18,
    marginBottom: spacing.sm,
  },
  button: {
    backgroundColor: colors.surface,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: colors.border,
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
  buttonDanger: {
    borderColor: colors.danger,
  },
  buttonText: {
    color: colors.accent,
    fontWeight: '600',
  },
  buttonTextDanger: {
    color: colors.danger,
  },
  cancel: {
    backgroundColor: 'transparent',
    borderColor: 'transparent',
  },
  cancelText: {
    color: colors.textDim,
    fontWeight: '600',
  },
});
