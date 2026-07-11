import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlaceAutocomplete } from './PlaceAutocomplete';

const mocks = vi.hoisted(() => ({ usePlaceSuggestions: vi.fn() }));
vi.mock('../hooks/usePlaceSuggestions', () => ({ usePlaceSuggestions: mocks.usePlaceSuggestions }));

const suggestion = {
  id: 'poi-1', name: '布达拉宫', address: '北京中路35号', district: '拉萨市城关区', city: '拉萨', province: '西藏自治区', type: 'attraction' as const, sourceType: '110200', latitude: '29.6427100', longitude: '91.1152800',
};

describe('PlaceAutocomplete', () => {
  beforeEach(() => {
    mocks.usePlaceSuggestions.mockReturnValue({ data: { suggestions: [suggestion] }, isError: false, isFetching: false });
  });

  it('shows suggestions after the debounce and selects with the keyboard', async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    function Harness() {
      const [value, setValue] = useState('');
      return <PlaceAutocomplete city="拉萨" onChange={setValue} onClearSelection={vi.fn()} onSelect={onSelect} selectedSuggestion={null} value={value} />;
    }
    render(<Harness />);
    const input = screen.getByLabelText('搜索地点');
    await user.type(input, '布达');
    expect(await screen.findByRole('option', { name: /布达拉宫/ })).toBeInTheDocument();
    await user.keyboard('{ArrowDown}{Enter}');
    expect(onSelect).toHaveBeenCalledWith(suggestion);
  });

  it('clears the selected coordinates when the name changes', async () => {
    const onClearSelection = vi.fn();
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<PlaceAutocomplete city="拉萨" onChange={onChange} onClearSelection={onClearSelection} onSelect={vi.fn()} selectedSuggestion={suggestion} value="布达拉宫" />);
    await user.type(screen.getByLabelText('搜索地点'), '新');
    expect(onClearSelection).toHaveBeenCalled();
    expect(onChange).toHaveBeenCalledWith('布达拉宫新');
  });
});
