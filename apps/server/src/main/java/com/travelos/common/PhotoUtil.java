package com.travelos.common;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;

/** Data-URL photo helpers: validation and thumbnail generation (mirrors photo-thumbnail.ts). */
public final class PhotoUtil {

  public static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;
  public static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
  public static final int DEFAULT_THUMBNAIL_WIDTH = 240;

  private PhotoUtil() {
  }

  /** @return base64 payload when the data URL is a supported image, otherwise null */
  public static String[] parsePhotoDataUrl(String url) {
    if (url == null) {
      return null;
    }
    var matcher = java.util.regex.Pattern
        .compile("^data:image/(jpeg|jpg|png|webp);base64,([a-zA-Z0-9+/=\\r\\n]+)$")
        .matcher(url);
    if (!matcher.matches()) {
      return null;
    }
    return new String[] {matcher.group(1), matcher.group(2)};
  }

  /** @return base64 payload for a document data URL (pdf or image), otherwise null */
  public static String[] parseDocumentDataUrl(String url) {
    if (url == null) {
      return null;
    }
    var matcher = java.util.regex.Pattern
        .compile("^data:(application/pdf|image/(?:jpeg|jpg|png|webp));base64,([a-zA-Z0-9+/=\\r\\n]+)$")
        .matcher(url);
    if (!matcher.matches()) {
      return null;
    }
    return new String[] {matcher.group(1), matcher.group(2)};
  }

  public static int base64Bytes(String base64) {
    return Math.floorDiv(base64.length() * 3, 4);
  }

  /** Resizes a data-URL image to the given max width, returning a JPEG data URL. */
  public static String createThumbnail(String dataUrl, int maxWidth) {
    String[] parts = parsePhotoDataUrl(dataUrl);
    if (parts == null) {
      throw new IllegalArgumentException("Unsupported photo data URL");
    }
    try {
      byte[] input = Base64.getDecoder().decode(parts[1].replaceAll("\\s", ""));
      BufferedImage source = ImageIO.read(new ByteArrayInputStream(input));
      if (source == null) {
        throw new IllegalArgumentException("Unsupported image");
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      var thumbnail = Thumbnails.of(source).width(Math.min(maxWidth, source.getWidth()));
      thumbnail.outputFormat("jpeg").outputQuality(0.72).toOutputStream(output);
      return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Thumbnail generation failed", ex);
    }
  }
}
