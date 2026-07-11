import { Injectable, NotFoundException } from '@nestjs/common';
import { Prisma, TripWeather } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { WeatherRepository } from './weather.repository';

const coordinateCacheTtlMs = 7 * 24 * 60 * 60 * 1000;
const weatherCacheTtlMs = 6 * 60 * 60 * 1000;
const maxForecastDays = 15;
const requestTimeoutMs = 8_000;

type Coordinates = { latitude: number; longitude: number; cachedAt: number };
type GeocodingResponse = {
  results?: { latitude: number; longitude: number }[];
};
type ForecastResponse = {
  daily?: {
    time?: string[];
    weather_code?: number[];
    temperature_2m_max?: number[];
    temperature_2m_min?: number[];
    precipitation_probability_max?: (number | null)[];
  };
};

@Injectable()
export class WeatherService {
  private readonly coordinateCache = new Map<string, Coordinates>();

  constructor(
    private readonly weatherRepository: WeatherRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async getTripWeather(tripId: string, userId: string) {
    await this.tripMembersService.requireTripMember(tripId, userId);
    const trip = await this.weatherRepository.findTrip(tripId);
    if (!trip) throw new NotFoundException('旅行不存在');

    const weather = await this.loadWeather(trip.id, trip.destination, trip.startDate, trip.endDate);
    if (!weather) return unavailableList();
    return { source: 'api' as const, weather: weather.map(toWeatherResponse) };
  }

  async getTripDayWeather(tripId: string, tripDayId: string, userId: string) {
    await this.tripMembersService.requireTripMember(tripId, userId);
    const [trip, day] = await Promise.all([
      this.weatherRepository.findTrip(tripId),
      this.weatherRepository.findTripDay(tripDayId),
    ]);
    if (!trip) throw new NotFoundException('旅行不存在');
    if (!day || day.tripId !== tripId) throw new NotFoundException('行程日不存在');

    const weather = await this.loadWeather(trip.id, trip.destination, trip.startDate, trip.endDate);
    const item = weather?.find((row) => dateKey(row.date) === dateKey(day.date));
    if (!item) return unavailableItem();
    return { source: 'api' as const, weather: toWeatherResponse(item) };
  }

  private async loadWeather(
    tripId: string,
    destination: string | null,
    startDate: Date | null,
    endDate: Date | null,
  ) {
    const normalizedDestination = destination?.trim();
    if (!normalizedDestination || !startDate) return null;

    const cached = await this.weatherRepository.findFreshWeather(
      tripId,
      normalizedDestination,
      new Date(Date.now() - weatherCacheTtlMs),
    );
    if (cached.length > 0) return cached;

    try {
      const dateRange = buildForecastDateRange(startDate, endDate ?? startDate);
      if (!dateRange) return null;
      const coordinates = await this.getCoordinates(normalizedDestination);
      if (!coordinates) return null;
      const forecast = await this.fetchForecast(coordinates, dateRange.start, dateRange.end);
      const tripDays = await this.weatherRepository.findTripDays(tripId);
      const dayByDate = new Map(tripDays.map((day) => [dateKey(day.date), day.id]));
      const fetchedAt = new Date();
      const rows = toWeatherRows(tripId, normalizedDestination, forecast, dayByDate, fetchedAt);
      await this.weatherRepository.replaceForecast(tripId, rows);
      return this.weatherRepository.findFreshWeather(
        tripId,
        normalizedDestination,
        new Date(fetchedAt.getTime() - 1_000),
      );
    } catch {
      return null;
    }
  }

  private async getCoordinates(destination: string) {
    const key = destination.toLocaleLowerCase('zh-CN');
    const cached = this.coordinateCache.get(key);
    if (cached && Date.now() - cached.cachedAt < coordinateCacheTtlMs) return cached;

    const url = new URL('https://geocoding-api.open-meteo.com/v1/search');
    url.searchParams.set('name', destination);
    url.searchParams.set('count', '1');
    url.searchParams.set('language', 'zh');
    url.searchParams.set('format', 'json');
    const response = await fetchWithTimeout(url);
    if (!response.ok) return null;
    const data = (await response.json()) as GeocodingResponse;
    const result = data.results?.[0];
    if (!result) return null;
    const coordinates = { ...result, cachedAt: Date.now() };
    this.coordinateCache.set(key, coordinates);
    return coordinates;
  }

  private async fetchForecast(coordinates: Coordinates, start: string, end: string) {
    const url = new URL('https://api.open-meteo.com/v1/forecast');
    url.searchParams.set('latitude', coordinates.latitude.toString());
    url.searchParams.set('longitude', coordinates.longitude.toString());
    url.searchParams.set(
      'daily',
      'weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max',
    );
    url.searchParams.set('timezone', 'auto');
    url.searchParams.set('start_date', start);
    url.searchParams.set('end_date', end);
    const response = await fetchWithTimeout(url);
    if (!response.ok) throw new Error('Open-Meteo request failed');
    return (await response.json()) as ForecastResponse;
  }
}

async function fetchWithTimeout(url: URL) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    return await fetch(url, { signal: controller.signal });
  } finally {
    clearTimeout(timeout);
  }
}

function buildForecastDateRange(startDate: Date, endDate: Date) {
  const today = toUtcDateOnly(new Date());
  const latest = new Date(today);
  latest.setUTCDate(latest.getUTCDate() + maxForecastDays);
  const start = maxDate(toUtcDateOnly(startDate), today);
  const end = minDate(toUtcDateOnly(endDate), latest);
  if (end < start) return null;
  return { start: apiDate(start), end: apiDate(end) };
}

function toWeatherRows(
  tripId: string,
  destination: string,
  forecast: ForecastResponse,
  dayByDate: Map<string, string>,
  fetchedAt: Date,
): Prisma.TripWeatherCreateManyInput[] {
  const daily = forecast.daily;
  const dates = daily?.time ?? [];
  return dates.flatMap((date, index) => {
    const high = daily?.temperature_2m_max?.[index];
    const low = daily?.temperature_2m_min?.[index];
    const code = daily?.weather_code?.[index];
    if (high === undefined || low === undefined || code === undefined) return [];
    const mapped = mapWeatherCode(code);
    const weatherDate = new Date(`${date}T00:00:00.000Z`);
    return [
      {
        tripId,
        tripDayId: dayByDate.get(date) ?? null,
        date: weatherDate,
        destination,
        condition: mapped.condition,
        tempHigh: high,
        tempLow: low,
        rainChance: daily?.precipitation_probability_max?.[index] ?? null,
        iconCode: mapped.iconCode,
        fetchedAt,
      },
    ];
  });
}

function mapWeatherCode(code: number) {
  if (code === 0) return { condition: '晴', iconCode: 'sun' };
  if ([1, 2, 3].includes(code)) return { condition: '多云', iconCode: 'cloud' };
  if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
    return { condition: '雨', iconCode: 'cloud-rain' };
  }
  if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) {
    return { condition: '雪', iconCode: 'snowflake' };
  }
  if (code >= 95) return { condition: '雷暴', iconCode: 'cloud-lightning' };
  return { condition: '其他', iconCode: 'cloud' };
}

function toWeatherResponse(weather: TripWeather) {
  return {
    id: weather.id,
    tripId: weather.tripId,
    tripDayId: weather.tripDayId,
    date: weather.date,
    destination: weather.destination,
    condition: weather.condition,
    tempHigh: Number(weather.tempHigh),
    tempLow: Number(weather.tempLow),
    rainChance: weather.rainChance,
    iconCode: weather.iconCode,
    fetchedAt: weather.fetchedAt,
  };
}

function unavailableList() {
  return { source: 'api' as const, weather: [], message: '暂无天气数据' };
}

function unavailableItem() {
  return { source: 'api' as const, weather: null, message: '暂无天气数据' };
}

function toUtcDateOnly(value: Date) {
  return new Date(Date.UTC(value.getUTCFullYear(), value.getUTCMonth(), value.getUTCDate()));
}

function maxDate(left: Date, right: Date) {
  return left > right ? left : right;
}

function minDate(left: Date, right: Date) {
  return left < right ? left : right;
}

function apiDate(value: Date) {
  return value.toISOString().slice(0, 10);
}

function dateKey(value: Date) {
  return value.toISOString().slice(0, 10);
}
