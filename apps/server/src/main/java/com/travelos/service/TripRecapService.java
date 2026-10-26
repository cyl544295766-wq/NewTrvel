package com.travelos.service;

import com.travelos.ai.LlmGatewayService;
import com.travelos.common.ApiException;
import com.travelos.entity.Photo;
import com.travelos.mapper.PhotoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * AI 行程回顾：一键把已结束行程（安排/花费/照片）交给 AI 生成一篇游记草稿。
 * 生成结果以「私密草稿」游记落库，用户在游记系统里编辑后再发布——不直接对外可见。
 */
@Service
public class TripRecapService {

  private static final Logger log = LoggerFactory.getLogger(TripRecapService.class);

  private final TripMemberService tripMemberService;
  private final TripService tripService;
  private final TripDayService tripDayService;
  private final TripExpenseService tripExpenseService;
  private final PhotoMapper photoMapper;
  private final TripJournalService tripJournalService;
  private final LlmGatewayService llm;

  public TripRecapService(TripMemberService tripMemberService, TripService tripService,
      TripDayService tripDayService, TripExpenseService tripExpenseService,
      PhotoMapper photoMapper, TripJournalService tripJournalService,
      LlmGatewayService llm) {
    this.tripMemberService = tripMemberService;
    this.tripService = tripService;
    this.tripDayService = tripDayService;
    this.tripExpenseService = tripExpenseService;
    this.photoMapper = photoMapper;
    this.tripJournalService = tripJournalService;
    this.llm = llm;
  }

  /** 生成行程回顾游记草稿，返回创建的游记（visibility=private, isDraft=true）。 */
  public Map<String, Object> generate(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    if (!llm.isEnabled()) {
      throw ApiException.serviceUnavailable(llm.disabledMessage());
    }

    /* ---- 组装行程事实数据 ---- */
    Map<String, Object> tripRes = tripService.findOne(tripId, userId);
    @SuppressWarnings("unchecked")
    Map<String, Object> trip = (Map<String, Object>) tripRes.get("trip");
    if (trip == null) {
      throw ApiException.notFound("旅行不存在");
    }
    SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
    dateFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

    Map<String, Object> daysRes = tripDayService.findAll(tripId, userId);
    StringBuilder daysText = new StringBuilder();
    int placeTotal = 0;
    if (daysRes.get("days") instanceof List<?> dayList) {
      for (Object item : dayList) {
        if (!(item instanceof Map<?, ?> rawDay)) {
          continue;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> day = (Map<String, Object>) rawDay;
        daysText.append("- 第 ").append(day.get("dayIndex")).append(" 天（")
            .append(day.get("date") instanceof Date d ? dateFmt.format(d) : "未知").append("）：");
        if (day.get("places") instanceof List<?> places && !places.isEmpty()) {
          List<String> names = new ArrayList<>();
          for (Object p : places) {
            if (p instanceof Map<?, ?> place) {
              names.add(String.valueOf(place.get("name")));
              placeTotal++;
            }
          }
          daysText.append(String.join(" → ", names));
        } else {
          daysText.append("（无安排）");
        }
        daysText.append("\n");
      }
    }

    String expenseSummary = "（无记账）";
    Map<String, Object> expensesRes = tripExpenseService.findAll(tripId, userId);
    if (expensesRes.get("expenses") instanceof List<?> expenses && !expenses.isEmpty()) {
      StringBuilder lines = new StringBuilder();
      int shown = 0;
      for (Object item : expenses) {
        if (item instanceof Map<?, ?> e) {
          lines.append("- ").append(e.get("title")).append("：")
              .append(e.get("amount")).append(" 元（").append(e.get("category")).append("）\n");
          if (++shown >= 10) {
            lines.append("- （其余略）\n");
            break;
          }
        }
      }
      Object total = expensesRes.get("summary") instanceof Map<?, ?> s ? s.get("totalAmount") : null;
      expenseSummary = "总花费 " + (total == null ? "未知" : total + " 元，明细：\n" + lines);
    }

    long photoCount = photoMapper.selectCount(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getTripId, tripId));

    /* ---- 构建提示词（单轮，无工具） ---- */
    String system = "你是一位细腻的中文旅行游记作者。请根据用户提供的真实行程数据，写一篇第一人称的行程回顾游记。"
        + "严格要求：\n"
        + "1. 第一行输出「标题：xxx」，标题 12 字以内、有画面感，不要书名号；\n"
        + "2. 随后空一行输出正文 400-700 字：自然融入旅程概览、每天的一两个亮点、花费小结与一句真诚收尾；\n"
        + "3. 只使用提供的真实数据，可以润色感受但不编造未提及的地点与数字；\n"
        + "4. 纯文本分段，不使用 Markdown 表格、加粗或列表符号；\n"
        + "5. 除「标题：」行外不要输出任何解释或前言。";
    String user = "行程数据：\n"
        + "行程名称：" + trip.get("title") + "\n"
        + "目的地：" + trip.get("destination") + "\n"
        + "日期：" + (trip.get("startDate") instanceof Date s ? dateFmt.format(s) : trip.get("startDate"))
        + " ~ " + (trip.get("endDate") instanceof Date e ? dateFmt.format(e) : trip.get("endDate")) + "\n"
        + "共 " + placeTotal + " 个行程点，留下 " + photoCount + " 张照片。\n"
        + "逐日安排：\n" + daysText + "\n"
        + "花费情况：" + expenseSummary + "\n"
        + "请生成行程回顾游记。";

    String content;
    try {
      Map<String, Object> reply = llm.chat(
          List.of(Map.of("role", "system", "content", system),
              Map.of("role", "user", "content", user)),
          List.of());
      content = reply.get("content") == null ? "" : reply.get("content").toString().trim();
    } catch (Exception ex) {
      log.warn("recap generation failed tripId={}", tripId, ex);
      throw ApiException.serviceUnavailable("AI 生成暂时不可用，请稍后再试");
    }
    if (content.isBlank()) {
      throw ApiException.serviceUnavailable("AI 未返回内容，请稍后再试");
    }

    /* ---- 解析「标题：」行 ---- */
    String title = trip.get("title") + " 回顾";
    String body = content;
    int newline = content.indexOf('\n');
    String firstLine = (newline > 0 ? content.substring(0, newline) : content).trim();
    if (firstLine.startsWith("标题：") || firstLine.startsWith("标题:")) {
      String parsed = firstLine.substring(3).trim();
      if (!parsed.isEmpty()) {
        title = parsed;
      }
      body = newline > 0 ? content.substring(newline + 1).trim() : content;
    }

    Map<String, Object> created = tripJournalService.create(tripId, userId, Map.of(
        "title", title,
        "content", body,
        "isDraft", true,
        "visibility", "private"));
    log.info("Trip recap journal created: trip={}, journal={}", tripId, created);
    return created;
  }
}
