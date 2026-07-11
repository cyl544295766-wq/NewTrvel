import { describe, expect, it } from 'vitest';
import { hasValidCoordinates } from './TripMap';

describe('hasValidCoordinates', () => {
  it('accepts valid coordinates', () => {
    expect(hasValidCoordinates({ latitude: '35.0116', longitude: '135.7681' })).toBe(true);
  });

  it('rejects empty, non-numeric, and out-of-range coordinates', () => {
    expect(hasValidCoordinates({ latitude: '', longitude: '' })).toBe(false);
    expect(hasValidCoordinates({ latitude: 'north', longitude: 'east' })).toBe(false);
    expect(hasValidCoordinates({ latitude: '91', longitude: '181' })).toBe(false);
  });
});
