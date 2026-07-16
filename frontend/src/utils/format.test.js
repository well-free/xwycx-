import { describe, expect, it } from 'vitest';
import { categoryFor, formatNumber, formatTime } from './format';

describe('commerce formatting', () => {
  it('formats currency with two decimals', () => {
    expect(formatNumber(12.8)).toBe('12.80');
    expect(formatNumber('not-a-number')).toBe('--');
  });

  it('classifies common catalog products', () => {
    expect(categoryFor({ name: '一次性餐盒', sku: 'BOX-10' })).toBe('DINING');
    expect(categoryFor({ name: '医用口罩', sku: 'MASK-50' })).toBe('CLEANING');
    expect(categoryFor({ name: '热敏标签纸', sku: 'OFFICE-1' })).toBe('OFFICE');
  });

  it('keeps empty timestamps readable', () => {
    expect(formatTime(null)).toBe('--');
  });
});
