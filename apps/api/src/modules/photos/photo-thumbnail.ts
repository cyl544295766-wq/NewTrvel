import { createHash } from 'node:crypto';
import sharp from 'sharp';

const defaultMaxWidth = 240;
const maxCachedThumbnails = 100;
const photoDataUrlPattern = /^data:image\/(jpeg|jpg|png|webp);base64,([a-zA-Z0-9+/=\r\n]+)$/;
const thumbnailCache = new Map<string, Promise<string>>();

type SupportedFormat = 'jpeg' | 'jpg' | 'png' | 'webp';

export async function createPhotoThumbnail(dataUrl: string, maxWidth = defaultMaxWidth) {
  const match = photoDataUrlPattern.exec(dataUrl);
  if (!match) {
    throw new Error('Unsupported photo data URL');
  }
  if (!Number.isInteger(maxWidth) || maxWidth <= 0) {
    throw new Error('Thumbnail width must be a positive integer');
  }

  const cacheKey = createHash('sha256').update(`${maxWidth}:${dataUrl}`).digest('base64url');
  const cachedThumbnail = thumbnailCache.get(cacheKey);
  if (cachedThumbnail) {
    return cachedThumbnail;
  }

  const thumbnail = resizePhoto(match[1] as SupportedFormat, match[2], maxWidth);
  thumbnailCache.set(cacheKey, thumbnail);
  trimThumbnailCache();

  try {
    return await thumbnail;
  } catch (error) {
    thumbnailCache.delete(cacheKey);
    throw error;
  }
}

async function resizePhoto(format: SupportedFormat, base64: string, maxWidth: number) {
  const input = Buffer.from(base64, 'base64');
  let pipeline = sharp(input, { failOn: 'error' }).rotate().resize({
    width: maxWidth,
    fit: 'inside',
    withoutEnlargement: true,
  });

  switch (format) {
    case 'jpeg':
    case 'jpg':
      pipeline = pipeline.jpeg({ quality: 72, progressive: true });
      break;
    case 'png':
      pipeline = pipeline.png({ compressionLevel: 9, palette: true, quality: 80 });
      break;
    case 'webp':
      pipeline = pipeline.webp({ quality: 72 });
      break;
  }

  const output = await pipeline.toBuffer();
  const outputFormat = format === 'jpg' ? 'jpeg' : format;
  return `data:image/${outputFormat};base64,${output.toString('base64')}`;
}

function trimThumbnailCache() {
  while (thumbnailCache.size > maxCachedThumbnails) {
    const oldestKey = thumbnailCache.keys().next().value;
    if (!oldestKey) {
      return;
    }
    thumbnailCache.delete(oldestKey);
  }
}
