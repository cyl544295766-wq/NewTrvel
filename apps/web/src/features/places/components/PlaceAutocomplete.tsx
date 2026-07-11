import { Check, LoaderCircle, MapPin, Search, X } from 'lucide-react';
import { KeyboardEvent, useEffect, useId, useRef, useState } from 'react';
import { usePlaceSuggestions } from '../hooks/usePlaceSuggestions';
import { PlaceSuggestion } from '../types/place.types';

type Props = {
  city?: string;
  onChange: (value: string) => void;
  onSelect: (suggestion: PlaceSuggestion) => void;
  onClearSelection: () => void;
  selectedSuggestion: PlaceSuggestion | null;
  value: string;
};

const typeLabels = { attraction: '景点', hotel: '酒店', restaurant: '餐厅', transport: '交通', shopping: '购物', custom: '其他' };

export function PlaceAutocomplete({ city, onChange, onSelect, onClearSelection, selectedSuggestion, value }: Props) {
  const [debouncedValue, setDebouncedValue] = useState(value);
  const [isOpen, setIsOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const listboxId = useId();
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const timeout = setTimeout(() => setDebouncedValue(value), 300);
    return () => clearTimeout(timeout);
  }, [value]);

  const query = usePlaceSuggestions(debouncedValue, city);
  const suggestions = query.data?.suggestions ?? [];

  useEffect(() => {
    function close(event: MouseEvent) {
      if (!rootRef.current?.contains(event.target as Node)) setIsOpen(false);
    }
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  useEffect(() => setActiveIndex(0), [debouncedValue]);

  function updateValue(nextValue: string) {
    if (selectedSuggestion && nextValue !== selectedSuggestion.name) onClearSelection();
    onChange(nextValue);
    setIsOpen(Boolean(nextValue.trim()));
  }

  function selectSuggestion(suggestion: PlaceSuggestion) {
    onSelect(suggestion);
    setIsOpen(false);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') { setIsOpen(false); return; }
    if (!isOpen || suggestions.length === 0) return;
    if (event.key === 'ArrowDown') { event.preventDefault(); setActiveIndex((current) => (current + 1) % suggestions.length); }
    if (event.key === 'ArrowUp') { event.preventDefault(); setActiveIndex((current) => (current - 1 + suggestions.length) % suggestions.length); }
    if (event.key === 'Enter') { event.preventDefault(); selectSuggestion(suggestions[activeIndex]); }
  }

  return (
    <div className="place-autocomplete" ref={rootRef}>
      <label htmlFor={`${listboxId}-input`}><span>搜索地点</span></label>
      <div className="place-search-input">
        <Search size={17} />
        <input
          aria-activedescendant={isOpen && suggestions[activeIndex] ? `${listboxId}-${suggestions[activeIndex].id}` : undefined}
          aria-autocomplete="list"
          aria-controls={listboxId}
          aria-expanded={isOpen}
          autoComplete="off"
          id={`${listboxId}-input`}
          maxLength={120}
          onChange={(event) => updateValue(event.target.value)}
          onFocus={() => setIsOpen(Boolean(value.trim()))}
          onKeyDown={handleKeyDown}
          placeholder="输入地点名称，例如：布达拉宫、清水寺"
          role="combobox"
          value={value}
        />
        {query.isFetching ? <LoaderCircle className="place-search-spinner" size={17} /> : null}
        {value ? <button aria-label="清空地点" onClick={() => updateValue('')} title="清空" type="button"><X size={16} /></button> : null}
      </div>

      {selectedSuggestion ? <div className="place-selection-status"><Check size={16} /><div><strong>{selectedSuggestion.name}</strong><span>{selectedSuggestion.address || selectedSuggestion.district || '已选择地点'}</span><small>{selectedSuggestion.latitude ? '已获取地图位置' : '该地点暂时无法自动定位'}</small></div></div> : null}

      {isOpen && debouncedValue.trim().length >= 2 && !selectedSuggestion ? (
        <div className="place-suggestion-popover" id={listboxId} role="listbox">
          {query.isFetching && !query.data ? <p className="place-search-message"><LoaderCircle size={15} />正在查找地点...</p> : null}
          {query.isError ? <div className="place-search-message is-error"><strong>地点搜索暂时不可用</strong><span>你仍然可以将“{value.trim()}”作为自定义地点保存。</span></div> : null}
          {!query.isFetching && !query.isError && suggestions.length === 0 ? <div className="place-search-message"><strong>没有找到“{value.trim()}”</strong><span>继续填写后可作为自定义地点保存。</span></div> : null}
          {suggestions.map((suggestion, index) => (
            <button aria-selected={activeIndex === index} className={activeIndex === index ? 'is-active' : ''} id={`${listboxId}-${suggestion.id}`} key={`${suggestion.id}-${index}`} onMouseDown={(event) => event.preventDefault()} onMouseEnter={() => setActiveIndex(index)} onClick={() => selectSuggestion(suggestion)} role="option" type="button">
              <span className="place-suggestion-icon"><MapPin size={16} /></span>
              <span className="place-suggestion-copy"><strong>{suggestion.name}</strong><small>{typeLabels[suggestion.type]}{suggestion.district ? ` · ${suggestion.district}` : ''}</small><em>{suggestion.address || (suggestion.latitude ? '可自动定位' : '暂时无法自动定位')}</em></span>
              {suggestion.latitude ? <Check size={15} /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
