package com.travelos.service;

import com.travelos.entity.TripDay;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板降级摘要（LLM 未配置/超时/失败时的兜底文案）与数字口径测试：
 * 覆盖「上午/下午/时间待定」计数、无风险措辞、行前简报措辞、无 NaN/undefined。
 */
class TodayBriefServiceTest {

  private static Map<String, Object> place(String name, Integer hour) {
    Map<String, Object> row = new HashMap<>();
    row.put("name", name);
    if (hour == null) {
      row.put("timed", false);
    } else {
      row.put("timed", true);
      row.put("startTime", Date.from(LocalDate.of(2026, 11, 3).atTime(hour, 0)
          .toInstant(ZoneOffset.ofHours(8))));
    }
    return row;
  }

  @Test
  void todayCountsAmPmAndTbd() {
    List<Map<String, Object>> schedule = List.of(
        place("上午甲", 9), place("上午乙", 11), place("下午丙", 14), place("待定丁", null));
    String summary = TodayBriefService.fallbackSummary("today", new TripDay(), schedule, 1,
        Map.of("unpackedItems", 2));
    assertTrue(summary.contains("今天共有 4 项安排，上午 2 项、下午 1 项"), summary);
    assertTrue(summary.contains("另有 1 项时间待定"), summary);
    assertTrue(summary.contains("1 条提醒"), summary);
    assertTrue(summary.contains("2 件物品未打包"), summary);
  }

  @Test
  void noRiskWording() {
    String summary = TodayBriefService.fallbackSummary("today", new TripDay(),
        List.of(place("甲", 9)), 0, Map.of("unpackedItems", 0));
    assertTrue(summary.contains("当前未发现需要特别处理的问题"), summary);
    assertFalse(summary.contains("undefined"), summary);
    assertFalse(summary.contains("NaN"), summary);
  }

  @Test
  void preTripWording() {
    TripDay day = new TripDay();
    day.setDayIndex(1);
    String summary = TodayBriefService.fallbackSummary("pre-trip", day,
        List.of(place("集合", 9)), 0, Map.of("unpackedItems", 0));
    assertTrue(summary.startsWith("行前简报"), summary);
    assertTrue(summary.contains("第 1 天共 1 项安排"), summary);
  }

  @Test
  void preTripWithoutDays() {
    String summary = TodayBriefService.fallbackSummary("pre-trip", null,
        List.of(), 0, Map.of("unpackedItems", 0));
    assertTrue(summary.contains("行程还没有安排"), summary);
    assertFalse(summary.contains("undefined"), summary);
    assertFalse(summary.contains("NaN"), summary);
  }
}
