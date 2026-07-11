import { Prisma, TripStatus } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { WeatherRepository } from './weather.repository';
import { WeatherService } from './weather.service';

describe('WeatherService', () => {
  const repository = {
    findTrip: jest.fn(),
    findTripDay: jest.fn(),
    findTripDays: jest.fn(),
    findFreshWeather: jest.fn(),
    replaceForecast: jest.fn(),
  };
  const membersService = { requireTripMember: jest.fn() };
  let service: WeatherService;

  beforeEach(() => {
    jest.clearAllMocks();
    service = new WeatherService(
      repository as unknown as WeatherRepository,
      membersService as unknown as TripMembersService,
    );
    jest.useFakeTimers().setSystemTime(new Date('2026-07-11T08:00:00+08:00'));
    membersService.requireTripMember.mockResolvedValue({ role: 'member' });
    repository.findTrip.mockResolvedValue(createTrip());
    repository.findTripDays.mockResolvedValue([
      { id: 'day-1', tripId: 'trip-1', date: new Date('2026-07-12T00:00:00.000Z') },
    ]);
    repository.replaceForecast.mockResolvedValue(undefined);
  });

  afterEach(() => {
    jest.restoreAllMocks();
    jest.useRealTimers();
  });

  it('fetches and stores weather data from Open-Meteo', async () => {
    const storedWeather = createWeather();
    repository.findFreshWeather
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([storedWeather]);
    jest
      .spyOn(global, 'fetch')
      .mockResolvedValueOnce(
        createJsonResponse({ results: [{ latitude: 31.23, longitude: 121.47 }] }),
      )
      .mockResolvedValueOnce(
        createJsonResponse({
          daily: {
            time: ['2026-07-12'],
            weather_code: [61],
            temperature_2m_max: [28.5],
            temperature_2m_min: [22.1],
            precipitation_probability_max: [70],
          },
        }),
      );

    const result = await service.getTripWeather('trip-1', 'user-1');

    expect(result.source).toBe('api');
    expect(result.weather).toEqual([
      expect.objectContaining({ condition: '雨', tempHigh: 28.5, rainChance: 70 }),
    ]);
    expect(repository.replaceForecast).toHaveBeenCalledWith(
      'trip-1',
      expect.arrayContaining([
        expect.objectContaining({
          tripDayId: 'day-1',
          condition: '雨',
          iconCode: 'cloud-rain',
        }),
      ]),
    );
  });

  it('returns fresh cached weather without calling Open-Meteo', async () => {
    repository.findFreshWeather.mockResolvedValue([createWeather()]);
    const fetchSpy = jest.spyOn(global, 'fetch');

    const result = await service.getTripWeather('trip-1', 'user-1');

    expect(result.weather).toHaveLength(1);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(repository.replaceForecast).not.toHaveBeenCalled();
  });

  it('returns an unavailable response when geocoding fails', async () => {
    repository.findFreshWeather.mockResolvedValue([]);
    jest.spyOn(global, 'fetch').mockResolvedValue(createJsonResponse({ results: [] }));

    const result = await service.getTripWeather('trip-1', 'user-1');

    expect(result).toEqual({ source: 'api', weather: [], message: '暂无天气数据' });
    expect(repository.replaceForecast).not.toHaveBeenCalled();
  });
});

function createTrip() {
  const now = new Date('2026-07-11T00:00:00.000Z');
  return {
    id: 'trip-1',
    title: '上海旅行',
    description: null,
    destination: '上海',
    startDate: new Date('2026-07-12T00:00:00.000Z'),
    endDate: new Date('2026-07-13T00:00:00.000Z'),
    status: TripStatus.planning,
    coverImageUrl: null,
    budget: null,
    ownerId: 'user-1',
    isFavorite: false,
    archivedAt: null,
    deletedAt: null,
    createdAt: now,
    updatedAt: now,
  };
}

function createWeather() {
  const now = new Date('2026-07-11T01:00:00.000Z');
  return {
    id: 'weather-1',
    tripId: 'trip-1',
    tripDayId: 'day-1',
    date: new Date('2026-07-12T00:00:00.000Z'),
    destination: '上海',
    condition: '雨',
    tempHigh: new Prisma.Decimal(28.5),
    tempLow: new Prisma.Decimal(22.1),
    rainChance: 70,
    iconCode: 'cloud-rain',
    fetchedAt: now,
    createdAt: now,
  };
}

function createJsonResponse(data: unknown) {
  return { ok: true, json: jest.fn().mockResolvedValue(data) } as unknown as Response;
}
