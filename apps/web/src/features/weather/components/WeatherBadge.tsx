import { TripWeather } from '../types/weather.types';
import { WeatherIcon } from './WeatherIcon';

type WeatherBadgeProps = { weather: TripWeather };

export function WeatherBadge({ weather }: WeatherBadgeProps) {
  return (
    <span className="weather-badge" title={`${weather.condition}，降雨概率 ${weather.rainChance ?? 0}%`}>
      <WeatherIcon code={weather.iconCode} size={17} />
      <strong>{Math.round(weather.tempHigh)}°</strong>
      <span>{weather.condition}</span>
    </span>
  );
}
