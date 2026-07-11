import { TripWeather } from '../types/weather.types';
import { WeatherCard } from './WeatherCard';

type WeatherForecastProps = {
  weather: TripWeather[];
  isLoading?: boolean;
};

export function WeatherForecast({ weather, isLoading = false }: WeatherForecastProps) {
  if (isLoading) return <p className="weather-empty">天气加载中...</p>;
  if (weather.length === 0) return <p className="weather-empty">暂无天气数据</p>;

  return (
    <section className="weather-forecast" aria-label="行程天气预报">
      <header>
        <div>
          <p className="eyebrow">天气</p>
          <h2>{weather[0].destination}天气预报</h2>
        </div>
        <span>数据来源：Open-Meteo</span>
      </header>
      <div className="weather-forecast-list">
        {weather.map((item) => (
          <WeatherCard key={`${item.tripId}-${item.date}`} weather={item} />
        ))}
      </div>
    </section>
  );
}
