import { ConfigService } from '@nestjs/config';
import { TripPlaceType } from '@prisma/client';
import { PlacesService } from './places.service';
import { gcj02ToWgs84 } from './utils/coordinate-transform';
import { mapAmapPlaceType } from './utils/place-type-mapper';

describe('PlacesService', () => {
  const config = { get: jest.fn().mockReturnValue('test-key') };
  let service: PlacesService;

  beforeEach(() => {
    jest.clearAllMocks();
    config.get.mockReturnValue('test-key');
    service = new PlacesService(config as unknown as ConfigService);
  });

  afterEach(() => jest.restoreAllMocks());

  it('normalizes, converts, limits, and deduplicates AMap suggestions', async () => {
    const fetchSpy = jest.spyOn(global, 'fetch').mockResolvedValue(createResponse({
      status: '1',
      tips: [
        { id: 'poi-1', name: '布达拉宫', district: '西藏自治区拉萨市城关区', address: '北京中路35号', location: '91.117212,29.646923', typecode: '110200' },
        { id: 'poi-1', name: '布达拉宫', location: '91.117212,29.646923', typecode: '110200' },
        { id: 'poi-2', name: '布达拉宫广场', typecode: '110000' },
      ],
    }));

    const result = await service.getSuggestions(' 布达 ', '拉萨', 2);

    expect(result.suggestions).toHaveLength(2);
    expect(result.suggestions[0]).toEqual(expect.objectContaining({ name: '布达拉宫', type: TripPlaceType.attraction, city: '拉萨' }));
    expect(result.suggestions[0].longitude).not.toBe('91.1172120');
    expect(result.suggestions[1].latitude).toBeNull();
    const requestedUrl = new URL(String(fetchSpy.mock.calls[0][0]));
    expect(requestedUrl.searchParams.get('keywords')).toBe('布达');
    expect(requestedUrl.searchParams.get('city')).toBe('拉萨');
  });

  it('uses the cache for repeated searches', async () => {
    const fetchSpy = jest.spyOn(global, 'fetch').mockResolvedValue(createResponse({ status: '1', tips: [] }));
    await service.getSuggestions('布达', '拉萨');
    await service.getSuggestions('布达', '拉萨');
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });
});

describe('place utilities', () => {
  it('maps AMap categories to Travel OS place types', () => {
    expect(mapAmapPlaceType('050100')).toBe(TripPlaceType.restaurant);
    expect(mapAmapPlaceType('100100')).toBe(TripPlaceType.hotel);
    expect(mapAmapPlaceType('150100')).toBe(TripPlaceType.transport);
    expect(mapAmapPlaceType('999999')).toBe(TripPlaceType.custom);
  });

  it('converts Chinese coordinates and leaves overseas coordinates unchanged', () => {
    const converted = gcj02ToWgs84(116.404, 39.915);
    expect(converted.longitude).toBeCloseTo(116.3978, 3);
    expect(converted.latitude).toBeCloseTo(39.9136, 3);
    expect(gcj02ToWgs84(2.3522, 48.8566)).toEqual({ longitude: 2.3522, latitude: 48.8566 });
  });
});

function createResponse(data: unknown) {
  return { ok: true, json: jest.fn().mockResolvedValue(data) } as unknown as Response;
}
