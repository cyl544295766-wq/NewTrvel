import sharp from 'sharp';
import { createPhotoThumbnail } from './photo-thumbnail';

describe('createPhotoThumbnail', () => {
  it.each([
    ['jpeg', 'jpeg'],
    ['png', 'png'],
    ['webp', 'webp'],
  ] as const)('creates a 240px-wide %s thumbnail', async (inputFormat, outputFormat) => {
    const source = await sharp({
      create: {
        width: 800,
        height: 400,
        channels: 3,
        background: { r: 40, g: 120, b: 200 },
      },
    })
      .toFormat(inputFormat)
      .toBuffer();
    const thumbnailUrl = await createPhotoThumbnail(
      `data:image/${inputFormat};base64,${source.toString('base64')}`,
    );
    const thumbnail = Buffer.from(thumbnailUrl.split(',')[1], 'base64');
    const metadata = await sharp(thumbnail).metadata();

    expect(thumbnailUrl).toMatch(new RegExp(`^data:image/${outputFormat};base64,`));
    expect(metadata.format).toBe(outputFormat);
    expect(metadata.width).toBe(240);
    expect(metadata.height).toBe(120);
    expect(thumbnail.length).toBeLessThan(source.length);
  });

  it('does not enlarge a photo narrower than 240px', async () => {
    const source = await sharp({
      create: {
        width: 120,
        height: 80,
        channels: 3,
        background: { r: 20, g: 40, b: 60 },
      },
    })
      .jpeg()
      .toBuffer();
    const thumbnailUrl = await createPhotoThumbnail(
      `data:image/jpeg;base64,${source.toString('base64')}`,
    );
    const metadata = await sharp(Buffer.from(thumbnailUrl.split(',')[1], 'base64')).metadata();

    expect(metadata.width).toBe(120);
    expect(metadata.height).toBe(80);
  });
});
