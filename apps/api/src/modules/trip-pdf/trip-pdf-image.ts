import sharp from 'sharp';

const imageDataUrlPattern = /^data:image\/(jpeg|jpg|png|webp);base64,([a-zA-Z0-9+/=\r\n]+)$/;

export async function preparePdfImage(dataUrl: string, maxWidth: number) {
  const match = imageDataUrlPattern.exec(dataUrl);
  if (!match) return null;

  try {
    const output = await sharp(Buffer.from(match[2], 'base64'), { failOn: 'error' })
      .rotate()
      .resize({ width: maxWidth, fit: 'inside', withoutEnlargement: true })
      .jpeg({ quality: 76, progressive: true })
      .toBuffer();

    return `data:image/jpeg;base64,${output.toString('base64')}`;
  } catch {
    return null;
  }
}
