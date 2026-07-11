export type WeatherIconCode =
  | 'sun'
  | 'cloud'
  | 'cloud-rain'
  | 'snowflake'
  | 'cloud-lightning';

export type TripWeather = {
  id: string;
  tripId: string;
  tripDayId: string | null;
  date: string;
  destination: string;
  condition: string;
  tempHigh: number;
  tempLow: number;
  rainChance: number | null;
  iconCode: WeatherIconCode | null;
  fetchedAt: string;
};

export type TripWeatherResponse = {
  source: 'api';
  weather: TripWeather[];
  message?: string;
};

export type TripDayWeatherResponse = {
  source: 'api';
  weather: TripWeather | null;
  message?: string;
};
