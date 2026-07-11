import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TripWeather } from '../types/weather.types';
import { WeatherBadge } from './WeatherBadge';
import { WeatherForecast } from './WeatherForecast';

describe('weather components', () => {
  it('renders the weather badge with temperature and mapped icon', () => {
    const { container } = render(<WeatherBadge weather={createWeather({ iconCode: 'sun' })} />);

    expect(screen.getByText('29°')).toBeInTheDocument();
    expect(screen.getByText('晴')).toBeInTheDocument();
    expect(container.querySelector('.lucide-sun')).toBeInTheDocument();
  });

  it('renders the trip weather forecast cards', () => {
    render(
      <WeatherForecast
        weather={[
          createWeather({ id: 'weather-1', iconCode: 'cloud-rain', condition: '雨' }),
          createWeather({ id: 'weather-2', date: '2026-07-13T00:00:00.000Z', rainChance: 20 }),
        ]}
      />,
    );

    expect(screen.getByRole('heading', { name: '上海天气预报' })).toBeInTheDocument();
    expect(screen.getByText('数据来源：Open-Meteo')).toBeInTheDocument();
    expect(screen.getByText('降雨概率 70%')).toBeInTheDocument();
  });

  it('renders the unavailable weather state', () => {
    render(<WeatherForecast weather={[]} />);
    expect(screen.getByText('暂无天气数据')).toBeInTheDocument();
  });
});

function createWeather(overrides: Partial<TripWeather> = {}): TripWeather {
  return {
    id: 'weather-1',
    tripId: 'trip-1',
    tripDayId: 'day-1',
    date: '2026-07-12T00:00:00.000Z',
    destination: '上海',
    condition: '晴',
    tempHigh: 28.6,
    tempLow: 22.1,
    rainChance: 70,
    iconCode: 'sun',
    fetchedAt: '2026-07-11T00:00:00.000Z',
    ...overrides,
  };
}
