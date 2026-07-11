import { CloudRain } from 'lucide-react';
import { TripWeather } from '../types/weather.types';
import { WeatherIcon } from './WeatherIcon';

type WeatherCardProps = { weather: TripWeather };

export function WeatherCard({ weather }: WeatherCardProps) {
  return (
    <article className="weather-card">
      <header>
        <div>
          <time>{formatWeatherDate(weather.date)}</time>
          <strong>{weather.condition}</strong>
        </div>
        <span className="weather-card-icon">
          <WeatherIcon code={weather.iconCode} size={28} />
        </span>
      </header>
      <div className="weather-temperatures">
        <strong>{Math.round(weather.tempHigh)}°</strong>
        <span>{Math.round(weather.tempLow)}°</span>
      </div>
      <p>
        <CloudRain aria-hidden="true" size={15} />
        降雨概率 {weather.rainChance ?? 0}%
      </p>
    </article>
  );
}

function formatWeatherDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    weekday: 'short',
  });
}
