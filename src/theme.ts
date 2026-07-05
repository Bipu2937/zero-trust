/**
 * Hand-rolled design tokens. Per the dependency-minimalism directive,
 * there is no UI library anywhere in this app — every component is
 * built from React Native core primitives only.
 */
export const colors = {
  background: '#0B0F14',
  surface: '#141B24',
  surfaceRaised: '#1B2530',
  border: '#22303F',
  text: '#E6EDF3',
  textDim: '#8B98A5',
  accent: '#4FD1C5',
  danger: '#F87171',
} as const;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
} as const;
