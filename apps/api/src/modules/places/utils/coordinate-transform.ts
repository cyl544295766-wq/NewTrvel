const pi = Math.PI;
const axis = 6378245.0;
const eccentricity = 0.006693421622965943;

export function gcj02ToWgs84(longitude: number, latitude: number) {
  if (outsideChina(longitude, latitude)) return { longitude, latitude };
  const delta = transform(longitude, latitude);
  return {
    longitude: longitude * 2 - delta.longitude,
    latitude: latitude * 2 - delta.latitude,
  };
}

function transform(longitude: number, latitude: number) {
  let latitudeDelta = transformLatitude(longitude - 105, latitude - 35);
  let longitudeDelta = transformLongitude(longitude - 105, latitude - 35);
  const radianLatitude = (latitude / 180) * pi;
  let magic = Math.sin(radianLatitude);
  magic = 1 - eccentricity * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  latitudeDelta = (latitudeDelta * 180) / (((axis * (1 - eccentricity)) / (magic * sqrtMagic)) * pi);
  longitudeDelta = (longitudeDelta * 180) / ((axis / sqrtMagic) * Math.cos(radianLatitude) * pi);
  return { longitude: longitude + longitudeDelta, latitude: latitude + latitudeDelta };
}

function outsideChina(longitude: number, latitude: number) {
  return longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271;
}

function transformLatitude(longitude: number, latitude: number) {
  let result = -100 + 2 * longitude + 3 * latitude + 0.2 * latitude * latitude + 0.1 * longitude * latitude + 0.2 * Math.sqrt(Math.abs(longitude));
  result += ((20 * Math.sin(6 * longitude * pi) + 20 * Math.sin(2 * longitude * pi)) * 2) / 3;
  result += ((20 * Math.sin(latitude * pi) + 40 * Math.sin((latitude / 3) * pi)) * 2) / 3;
  result += ((160 * Math.sin((latitude / 12) * pi) + 320 * Math.sin((latitude * pi) / 30)) * 2) / 3;
  return result;
}

function transformLongitude(longitude: number, latitude: number) {
  let result = 300 + longitude + 2 * latitude + 0.1 * longitude * longitude + 0.1 * longitude * latitude + 0.1 * Math.sqrt(Math.abs(longitude));
  result += ((20 * Math.sin(6 * longitude * pi) + 20 * Math.sin(2 * longitude * pi)) * 2) / 3;
  result += ((20 * Math.sin(longitude * pi) + 40 * Math.sin((longitude / 3) * pi)) * 2) / 3;
  result += ((150 * Math.sin((longitude / 12) * pi) + 300 * Math.sin((longitude / 30) * pi)) * 2) / 3;
  return result;
}
