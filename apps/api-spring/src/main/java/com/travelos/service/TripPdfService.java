package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.entity.Photo;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.trip.TripPermission;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

@Service
public class TripPdfService {

  private static final int MAX_SELECTED_PHOTOS = 12;
  private static final Color INK = new Color(0x17, 0x25, 0x1C);
  private static final Color MUTED = new Color(0x68, 0x70, 0x68);
  private static final Color GREEN = new Color(0x31, 0x5F, 0x45);
  private static final Color ROW = new Color(0xF5, 0xF7, 0xF3);
  private static final float MARGIN = 48f;
  private static final float PAGE_WIDTH = 595f;
  private static final float PAGE_HEIGHT = 842f;
  private static final float CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2;

  private final TripMapper tripMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripExpenseMapper expenseMapper;
  private final PhotoMapper photoMapper;
  private final TripMemberService tripMemberService;

  public TripPdfService(TripMapper tripMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripExpenseMapper expenseMapper, PhotoMapper photoMapper,
      TripMemberService tripMemberService) {
    this.tripMapper = tripMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.expenseMapper = expenseMapper;
    this.photoMapper = photoMapper;
    this.tripMemberService = tripMemberService;
  }

  public Map<String, Object> export(String tripId, String userId) throws java.io.IOException {
    Trip trip = tripMapper.selectById(tripId);
    if (trip == null || trip.getDeletedAt() != null) {
      throw ApiException.notFound("旅行不存在");
    }
    try {
      tripMemberService.requireTripMember(tripId, userId);
    } catch (ApiException ex) {
      if (ex.getStatusCode() == 404) {
        throw ApiException.forbidden("没有权限导出该旅行");
      }
      throw ex;
    }

    List<TripDay> days = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId).orderByAsc(TripDay::getDayIndex));
    Map<String, List<TripPlace>> placesByDay = new LinkedHashMap<>();
    for (TripDay day : days) {
      placesByDay.put(day.getId(), tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
          .eq(TripPlace::getTripDayId, day.getId())
          .orderByAsc(TripPlace::getSortOrder)));
    }
    List<TripExpense> expenses = expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
        .eq(TripExpense::getTripId, tripId)
        .orderByDesc(TripExpense::getSpentAt));
    List<Photo> photos = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getTripId, tripId)
        .orderByDesc(Photo::getIsCover)
        .orderByDesc(Photo::getShotAt)
        .orderByDesc(Photo::getCreatedAt));

    try (PDDocument document = new PDDocument()) {
      PDFont font = loadFont(document);
      Writer writer = new Writer(document, font);
      writer.writeCoverPage(trip, findCoverUrl(tripId, trip.getCoverImageUrl()));
      for (TripDay day : days) {
        writer.writeDayPage(day, placesByDay.getOrDefault(day.getId(), List.of()));
      }
      writer.writeExpensePage(expenses);
      writer.writePhotoPages(photos);
      writer.finish();

      ByteArrayOutputStream output = new ByteArrayOutputStream();
      document.save(output);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("buffer", output.toByteArray());
      result.put("filename", sanitizeFilename(trip.getTitle()) + ".pdf");
      return result;
    }
  }

  private PDFont loadFont(PDDocument document) throws java.io.IOException {
    try (InputStream input = new ClassPathResource("fonts/NotoSansSC-VF.ttf").getInputStream()) {
      return PDType0Font.load(document, input, true);
    } catch (Exception ignored) {
      // fall through to system fonts
    }
    File simsun = new File("C:/Windows/Fonts/simsun.ttc");
    if (simsun.exists()) {
      try (TrueTypeCollection collection = new TrueTypeCollection(simsun)) {
        return PDType0Font.load(document, collection.getFontByName("SimSun"), true);
      } catch (Exception ignored) {
        // fall through
      }
    }
    throw new java.io.IOException("PDF 中文字体加载失败");
  }

  private String findCoverUrl(String tripId, String fallback) {
    Photo cover = photoMapper.selectOne(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getTripId, tripId)
        .eq(Photo::getIsCover, true)
        .orderByDesc(Photo::getUpdatedAt)
        .last("LIMIT 1"));
    return cover != null ? cover.getUrl() : fallback;
  }

  private String sanitizeFilename(String value) {
    String sanitized = value == null ? "" : value.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "").trim();
    if (sanitized.length() > 80) {
      sanitized = sanitized.substring(0, 80);
    }
    return sanitized.isEmpty() ? "旅行行程" : sanitized;
  }

  private String formatDate(Date date) {
    if (date == null) {
      return null;
    }
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTime(date);
    return calendar.get(Calendar.YEAR) + "年" + (calendar.get(Calendar.MONTH) + 1) + "月"
        + calendar.get(Calendar.DAY_OF_MONTH) + "日";
  }

  private String formatTimeRange(Date start, Date end) {
    String startText = formatTime(start);
    String endText = formatTime(end);
    if (startText == null && endText == null) {
      return null;
    }
    return (startText == null ? "" : startText) + " - " + (endText == null ? "" : endText);
  }

  private String formatTime(Date value) {
    if (value == null) {
      return null;
    }
    SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.ROOT);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format.format(value);
  }

  private class Writer {

    private final PDDocument document;
    private final PDFont font;
    private PDPageContentStream content;
    private float y;
    private int pageNumber = 0;

    Writer(PDDocument document, PDFont font) throws java.io.IOException {
      this.document = document;
      this.font = font;
      newPage();
    }

    private void newPage() throws java.io.IOException {
      closeContent();
      document.addPage(new PDPage(PDRectangle.A4));
      pageNumber++;
      content = new PDPageContentStream(document, document.getPage(pageNumber - 1));
      y = PAGE_HEIGHT - 54;
      writeFooter();
    }

    private void closeContent() {
      if (content != null) {
        try {
          content.close();
        } catch (java.io.IOException ignored) {
          // already closed
        }
        content = null;
      }
    }

    private void writeFooter() throws java.io.IOException {
      text("Travel OS", 8, MUTED, false, MARGIN, 22);
      String pageText = pageNumber + " / " + pageNumber;
      float width = font.getStringWidth(pageText) / 1000 * 8;
      text(pageText, 8, MUTED, false, PAGE_WIDTH - MARGIN - width, 22);
    }

    void writeCoverPage(Trip trip, String coverUrl) throws java.io.IOException {
      y -= 36;
      text("TRAVEL OS · 行程手册", 9, GREEN, true, MARGIN, y);
      y -= 12;
      text(trip.getTitle(), 34, INK, true, MARGIN, y);
      y -= 16;
      text(trip.getDestination() == null || trip.getDestination().isBlank()
          ? "目的地未设置" : trip.getDestination(), 17, new Color(0x45, 0x60, 0x4E), false, MARGIN, y);
      y -= 12;
      text(formatDateRange(trip.getStartDate(), trip.getEndDate()), 12, MUTED, false, MARGIN, y);
      y -= 40;

      if (coverUrl != null) {
        PDImageXObject image = tryLoadImage(coverUrl);
        if (image != null) {
          float[] fitted = fit(image, Math.min(CONTENT_WIDTH, 499), 300);
          content.drawImage(image, (PAGE_WIDTH - fitted[0]) / 2, y - fitted[1], fitted[0], fitted[1]);
          y -= fitted[1] + 12;
        }
      } else if (trip.getDescription() != null && !trip.getDescription().isBlank()) {
        text(trip.getDescription(), 12, new Color(0x4F, 0x5B, 0x52), false, MARGIN, y);
      }
      text("由 Travel OS 生成", 9, MUTED, false, MARGIN, 70);
      pageBreak();
    }

    void writeDayPage(TripDay day, List<TripPlace> places) throws java.io.IOException {
      text("第 " + day.getDayIndex() + " 天", 9, GREEN, true, MARGIN, y);
      y -= 12;
      text(day.getTitle() == null || day.getTitle().isBlank() ? formatDate(day.getDate())
          : day.getTitle(), 25, INK, true, MARGIN, y);
      y -= 14;
      text(formatDate(day.getDate()), 10, MUTED, false, MARGIN, y);
      y -= 16;
      if (day.getSummary() != null && !day.getSummary().isBlank()) {
        text(day.getSummary(), 10, INK, false, MARGIN, y);
        y -= 18;
      }
      if (places.isEmpty()) {
        text("当天暂未安排地点。", 10, MUTED, false, MARGIN, y);
      } else {
        int index = 0;
        for (TripPlace place : places) {
          if (y - 72 < 40) {
            pageBreak();
          }
          content.setNonStrokingColor(index % 2 == 0 ? ROW : Color.WHITE);
          content.addRect(MARGIN, y - 62, CONTENT_WIDTH, 62);
          content.fill();
          content.setNonStrokingColor(Color.WHITE);
          String timeLabel = formatTimeRange(place.getStartTime(), place.getEndTime());
          text(timeLabel == null || timeLabel.isEmpty()
              ? String.format(Locale.ROOT, "%02d", index + 1) : timeLabel, 10, GREEN, true,
              MARGIN + 6, y - 18);
          text(place.getName(), 12, INK, true, MARGIN + 82, y - 18);
          float lineY = y - 34;
          if (place.getAddress() != null && !place.getAddress().isBlank()) {
            text(place.getAddress(), 9, MUTED, false, MARGIN + 82, lineY);
            lineY -= 13;
          }
          if (place.getNotes() != null && !place.getNotes().isBlank()) {
            text(place.getNotes(), 10, INK, false, MARGIN + 82, lineY);
          }
          y -= 72;
          index++;
        }
      }
      pageBreak();
    }

    void writeExpensePage(List<TripExpense> expenses) throws java.io.IOException {
      text("费用汇总", 9, GREEN, true, MARGIN, y);
      y -= 12;
      text("旅行花费", 25, INK, true, MARGIN, y);
      y -= 22;
      text("总花费", 15, INK, true, MARGIN, y);
      y -= 24;
      String totals = sumByCurrency(expenses);
      text(totals.isEmpty() ? "暂无费用记录" : totals, 22, GREEN, true, MARGIN, y);
      y -= 28;
      text("分类统计", 15, INK, true, MARGIN, y);
      y -= 18;

      LinkedHashMap<String, Double> categories = new LinkedHashMap<>();
      for (TripExpense expense : expenses) {
        String label = categoryLabel(expense.getCategory());
        categories.merge(label + "|" + expense.getCurrency(), expense.getAmount().doubleValue(),
            Double::sum);
      }
      if (categories.isEmpty()) {
        text("暂无费用记录", 10, MUTED, false, MARGIN, y);
      } else {
        // table header
        content.setNonStrokingColor(GREEN);
        content.addRect(MARGIN, y - 24, CONTENT_WIDTH, 24);
        content.fill();
        text("分类", 10, Color.WHITE, true, MARGIN + 6, y - 17);
        text("币种", 10, Color.WHITE, true, MARGIN + 220, y - 17);
        text("金额", 10, Color.WHITE, true, PAGE_WIDTH - MARGIN - 80, y - 17);
        y -= 24;
        int rowIndex = 0;
        for (Map.Entry<String, Double> entry : categories.entrySet()) {
          String[] parts = entry.getKey().split("\\|");
          if (y - 22 < 40) {
            pageBreak();
          }
          content.setNonStrokingColor(rowIndex % 2 == 0 ? ROW : Color.WHITE);
          content.addRect(MARGIN, y - 22, CONTENT_WIDTH, 22);
          content.fill();
          content.setNonStrokingColor(Color.WHITE);
          text(parts[0], 10, INK, false, MARGIN + 6, y - 16);
          text(parts.length > 1 ? parts[1] : "", 10, INK, false, MARGIN + 220, y - 16);
          String amount = String.format(Locale.ROOT, "%.2f", entry.getValue());
          float width = stringWidth(amount, 10);
          text(amount, 10, INK, false, PAGE_WIDTH - MARGIN - width - 6, y - 16);
          y -= 22;
          rowIndex++;
        }
      }
      pageBreak();
    }

    void writePhotoPages(List<Photo> photos) throws java.io.IOException {
      text("照片精选", 9, GREEN, true, MARGIN, y);
      y -= 12;
      text("旅行影像", 25, INK, true, MARGIN, y);
      y -= 22;
      List<Photo> selected = photos.subList(0, Math.min(MAX_SELECTED_PHOTOS, photos.size()));
      if (selected.isEmpty()) {
        text("暂无照片。", 10, MUTED, false, MARGIN, y);
        return;
      }
      Map<String, List<Photo>> groups = new LinkedHashMap<>();
      for (Photo photo : selected) {
        Date date = photo.getShotAt() != null ? photo.getShotAt()
            : photo.getCreatedAt() == null ? new Date() : photo.getCreatedAt();
        groups.computeIfAbsent(formatDate(date), key -> new ArrayList<>()).add(photo);
      }
      float cellWidth = CONTENT_WIDTH / 3;
      for (Map.Entry<String, List<Photo>> group : groups.entrySet()) {
        if (y - 180 < 40) {
          pageBreak();
        }
        text(group.getKey(), 15, INK, true, MARGIN, y);
        y -= 16;
        List<Photo> items = group.getValue();
        for (int index = 0; index < items.size(); index += 3) {
          if (y - 140 < 40) {
            pageBreak();
          }
          for (int cell = 0; cell < 3 && index + cell < items.size(); cell++) {
            Photo photo = items.get(index + cell);
            float x = MARGIN + cell * cellWidth;
            PDImageXObject image = tryLoadImage(photo.getUrl());
            if (image != null) {
              float[] fitted = fit(image, cellWidth - 12, 105);
              content.drawImage(image, x + (cellWidth - fitted[0]) / 2, y - fitted[1] - 6,
                  fitted[0], fitted[1]);
            }
            String caption = photo.getCaption() == null || photo.getCaption().isBlank()
                ? "旅行照片" : photo.getCaption();
            text(caption, 8, MUTED, false, x, y - 124);
          }
          y -= 140;
        }
        y -= 10;
      }
    }

    void finish() {
      closeContent();
    }

    private void pageBreak() throws java.io.IOException {
      newPage();
    }

    private void text(String value, float size, Color color, boolean bold, float x, float baseline)
        throws java.io.IOException {
      if (value == null || value.isEmpty()) {
        return;
      }
      content.beginText();
      content.setFont(font, size);
      content.setNonStrokingColor(color);
      content.newLineAtOffset(x, baseline);
      content.showText(value);
      content.endText();
      if (x == MARGIN && baseline == y) {
        y -= size * 1.45f;
      }
    }

    private float stringWidth(String value, float size) throws java.io.IOException {
      return font.getStringWidth(value) / 1000 * size;
    }

    private PDImageXObject tryLoadImage(String dataUrl) {
      try {
        String[] parts = dataUrl.split(",", 2);
        if (parts.length != 2) {
          return null;
        }
        byte[] bytes = Base64.getDecoder().decode(parts[1].replaceAll("\\s", ""));
        return PDImageXObject.createFromByteArray(document, bytes, null);
      } catch (Exception ex) {
        return null;
      }
    }

    private float[] fit(PDImageXObject image, float maxWidth, float maxHeight) {
      float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
      return new float[] {image.getWidth() * scale, image.getHeight() * scale};
    }

    private String sumByCurrency(List<TripExpense> expenses) {
      Map<String, Double> totals = new LinkedHashMap<>();
      for (TripExpense expense : expenses) {
        totals.merge(expense.getCurrency(), expense.getAmount().doubleValue(), Double::sum);
      }
      StringBuilder builder = new StringBuilder();
      for (Map.Entry<String, Double> entry : totals.entrySet()) {
        if (builder.length() > 0) {
          builder.append(" / ");
        }
        builder.append(entry.getKey()).append(" ")
            .append(String.format(Locale.ROOT, "%.2f", entry.getValue()));
      }
      return builder.toString();
    }

    private String categoryLabel(String category) {
      return switch (category) {
        case "transport" -> "交通";
        case "hotel" -> "住宿";
        case "food" -> "餐饮";
        case "ticket" -> "门票";
        case "parking" -> "停车";
        case "shopping" -> "购物";
        case "maintenance" -> "维修";
        case "activity" -> "活动";
        default -> "其他";
      };
    }

    private String formatDateRange(Date startDate, Date endDate) {
      if (startDate == null && endDate == null) {
        return "日期未设置";
      }
      return (startDate == null ? "" : formatDate(startDate)) + " 至 "
          + (endDate == null ? "" : formatDate(endDate));
    }
  }
}
