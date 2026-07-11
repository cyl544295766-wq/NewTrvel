import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PlaceSuggestion } from './types/place-suggestion.type';
import { gcj02ToWgs84 } from './utils/coordinate-transform';
import { mapAmapPlaceType } from './utils/place-type-mapper';

const requestTimeoutMs = 6_000;
const cacheTtlMs = 10 * 60 * 1000;

type AmapTip = {
  id?: string;
  name?: string;
  district?: string;
  address?: string | string[];
  location?: string;
  typecode?: string;
};

type AmapInputTipsResponse = {
  status?: string;
  info?: string;
  tips?: AmapTip[];
};

type CachedSuggestions = { expiresAt: number; suggestions: PlaceSuggestion[] };

@Injectable()
export class PlacesService {
  private readonly cache = new Map<string, CachedSuggestions>();

  constructor(private readonly configService: ConfigService) {}

  async getSuggestions(keyword: string, city?: string, limit = 8) {
    const normalizedKeyword = keyword.trim();
    const normalizedCity = city?.trim() || undefined;
    const cacheKey = `${normalizedKeyword.toLocaleLowerCase('zh-CN')}|${normalizedCity ?? ''}|${limit}`;
    const cached = this.cache.get(cacheKey);
    if (cached && cached.expiresAt > Date.now()) return { suggestions: cached.suggestions };

    const key = this.configService.get<string>('AMAP_WEB_SERVICE_KEY')?.trim();
    if (!key) throw new ServiceUnavailableException('地点搜索服务尚未配置');

    try {
      const url = new URL('https://restapi.amap.com/v3/assistant/inputtips');
      url.searchParams.set('key', key);
      url.searchParams.set('keywords', normalizedKeyword);
      url.searchParams.set('datatype', 'all');
      if (normalizedCity) {
        url.searchParams.set('city', normalizedCity);
        url.searchParams.set('citylimit', 'true');
      }
      const response = await fetchWithTimeout(url);
      if (!response.ok) throw new Error('Upstream request failed');
      const data = (await response.json()) as AmapInputTipsResponse;
      if (data.status !== '1') throw new Error('Upstream response rejected');
      const suggestions = normalizeSuggestions(data.tips ?? [], normalizedCity).slice(0, limit);
      this.cache.set(cacheKey, { expiresAt: Date.now() + cacheTtlMs, suggestions });
      return { suggestions };
    } catch (error) {
      if (error instanceof ServiceUnavailableException) throw error;
      throw new ServiceUnavailableException('地点搜索暂时不可用');
    }
  }
}

function normalizeSuggestions(tips: AmapTip[], city?: string) {
  const seen = new Set<string>();
  return tips.flatMap<PlaceSuggestion>((tip) => {
    const name = tip.name?.trim();
    if (!name) return [];
    const coordinates = parseLocation(tip.location);
    const identity = `${tip.id ?? ''}|${name}|${coordinates?.longitude ?? ''}|${coordinates?.latitude ?? ''}`;
    if (seen.has(identity)) return [];
    seen.add(identity);
    const address = Array.isArray(tip.address) ? tip.address.join('') : tip.address?.trim();
    return [{
      id: tip.id || identity,
      name,
      address: address || null,
      district: tip.district?.trim() || null,
      city: city || null,
      province: null,
      type: mapAmapPlaceType(tip.typecode),
      sourceType: tip.typecode || null,
      latitude: coordinates?.latitude.toFixed(7) ?? null,
      longitude: coordinates?.longitude.toFixed(7) ?? null,
    }];
  });
}

function parseLocation(location?: string) {
  if (!location || !location.includes(',')) return null;
  const [longitude, latitude] = location.split(',').map(Number);
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) return null;
  return gcj02ToWgs84(longitude, latitude);
}

async function fetchWithTimeout(url: URL) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
  try { return await fetch(url, { signal: controller.signal }); }
  finally { clearTimeout(timeout); }
}
