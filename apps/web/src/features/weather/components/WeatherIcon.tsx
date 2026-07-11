import { Cloud, CloudLightning, CloudRain, Snowflake, Sun } from 'lucide-react';
import { WeatherIconCode } from '../types/weather.types';

type WeatherIconProps = {
  code: WeatherIconCode | null;
  size?: number;
};

export function WeatherIcon({ code, size = 20 }: WeatherIconProps) {
  const Icon =
    code === 'sun'
      ? Sun
      : code === 'cloud-rain'
        ? CloudRain
        : code === 'snowflake'
          ? Snowflake
          : code === 'cloud-lightning'
            ? CloudLightning
            : Cloud;
  return <Icon aria-hidden="true" size={size} strokeWidth={1.9} />;
}
