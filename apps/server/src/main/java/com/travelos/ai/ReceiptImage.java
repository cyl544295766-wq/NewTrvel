package com.travelos.ai;

import com.travelos.common.ApiException;
import com.travelos.common.PhotoUtil;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

record ReceiptImage(byte[] bytes, String format) {
  static final int MAX_BYTES = 4 * 1024 * 1024;
  private static final int MAX_BASE64 = ((MAX_BYTES + 2) / 3) * 4;
  private static final int MAX_SIDE = 6_000;
  private static final long MAX_PIXELS = 12_000_000;
  private static final int MODEL_SIDE = 2_400;

  static ReceiptImage fromUpload(String dataUrl) {
    if (dataUrl.length() > MAX_BASE64 + 32) {
      throw ApiException.badRequest("图片大小不能超过 4 MB");
    }
    String[] parts = PhotoUtil.parsePhotoDataUrl(dataUrl);
    if (parts == null) {
      throw invalidImage();
    }
    try {
      return validate(Base64.getDecoder().decode(parts[1]), parts[0]);
    } catch (IllegalArgumentException ex) {
      throw invalidImage();
    }
  }

  static ReceiptImage fromManaged(Path path, String filename) {
    try (InputStream input = Files.newInputStream(path)) {
      return validate(input.readNBytes(MAX_BYTES + 1), filename.substring(filename.lastIndexOf('.') + 1));
    } catch (IOException ex) {
      throw ApiException.notFound("图片不存在");
    }
  }

  private static ReceiptImage validate(byte[] bytes, String format) {
    if (bytes.length > MAX_BYTES) {
      throw ApiException.badRequest("图片大小不能超过 4 MB");
    }
    String expected = "jpg".equals(format) ? "jpeg" : format;
    if (!("jpeg".equals(expected) || "png".equals(expected))) {
      throw invalidImage();
    }
    return new ReceiptImage(bytes, expected);
  }

  /** 验证实际格式和像素上限，再重新编码去除元数据和附带内容。 */
  String forModel() {
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw invalidImage();
      }
      var reader = readers.next();
      try {
        if (!format.equals(reader.getFormatName().toLowerCase(Locale.ROOT))) {
          throw invalidImage();
        }
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
          throw ApiException.badRequest("图片尺寸过大，请裁剪或缩小后重试");
        }
        double scale = Math.min(1.0, (double) MODEL_SIDE / Math.max(width, height));
        var image = new BufferedImage(Math.max(1, (int) (width * scale)),
            Math.max(1, (int) (height * scale)), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
          graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
          graphics.drawImage(reader.read(0), 0, 0, image.getWidth(), image.getHeight(), Color.WHITE, null);
        } finally {
          graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
      } finally {
        reader.dispose();
      }
    } catch (IOException ex) {
      throw invalidImage();
    }
  }

  String fingerprint() {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static ApiException invalidImage() {
    return ApiException.badRequest("请选择有效的 JPEG 或 PNG 票据图片");
  }
}
