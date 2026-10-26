package com.travelos.ai;

import com.travelos.entity.AiConversation;
import com.travelos.service.AiPreferenceService;
import com.travelos.service.TripDayService;
import com.travelos.service.TripService;
import com.travelos.service.WeatherService;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * AI 系统提示词构建器：拼装规则（18 条）、用户旅行偏好、行程上下文与天气预报。
 * 从 AiChatService 拆出，职责单一——「模型每轮看到的 system 消息」都由这里生成；
 * 另提供全局对话默认行程（最近已规划行程）的解析，供工具层与回复兜底复用。
 */
@Component
public class AiPromptBuilder {

  private final TripService tripService;
  private final TripDayService tripDayService;
  private final WeatherService weatherService;
  private final AiPreferenceService preferenceService;

  public AiPromptBuilder(TripService tripService, TripDayService tripDayService,
      WeatherService weatherService, AiPreferenceService preferenceService) {
    this.tripService = tripService;
    this.tripDayService = tripDayService;
    this.weatherService = weatherService;
    this.preferenceService = preferenceService;
  }

  private String buildSystemPrompt(AiConversation conversation) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("你是 Travel OS 的 AI 旅行助手，帮助用户规划行程、查询信息、记录开销。");
    prompt.append("规则：\n");
    prompt.append("1. 行程变更必须通过 propose_itinerary_change 工具生成草稿，绝不直接写行程；用户确认后才生效。")
        .append("新增或修改地点时，先用 search_places 查询该地点，把返回的 latitude/longitude 写入草稿的 change 项（名称也不要改动为其他城市同名地点），确保应用后地图能定位。")
        .append("重新规划某一整天的行程时，工具参数 replaceDay 传 true：应用时会先清空当天原有安排再写入，")
        .append("避免同一天多次应用后新行程与旧行程叠加（历史 bug：一天出现多段行程）；只是往某天追加单个地点时传 false。\n");
    prompt.append("2. 记账必须通过 add_expense 工具生成草稿，并在回复中向用户复述关键信息（类别、金额、分摊方式），用户确认后才生效。\n");
    prompt.append("3. 回答使用简体中文，简洁友好；不确定时先用工具查询再回答。\n");
    prompt.append("4. 金额一律人民币，不做汇率折算。\n");
    prompt.append("5. 话题范围：你只提供旅游相关服务（行程规划、目的地建议、记账、打包、预订、游记等）。")
        .append("与旅游无关的问题（写代码、写作业、时事、闲聊、医疗/法律/投资建议等）一律不要回答，")
        .append("直接委婉引导回旅游话题，例如：「很抱歉，我专注于为您提供旅游服务，请问有什么旅游方面可以帮到您的吗？」")
        .append("可以顺势介绍你能帮忙的事，但语气保持自然友好、不生硬。\n");
    prompt.append("6. 页面导航：当用户明确想查看或打开系统中的某个页面（如「打开行程规划」「打开预定管理」「看看我的花费」「去记账」），")
        .append("调用 open_page 工具选择最匹配的页面（预定/预订相关选 bookings，不要选 trips），并在回复中用一句话自然告知即将打开的页面（如「好的，这就带你去行程规划～」）。")
        .append("普通旅行咨询（攻略、建议、问答）不要调用 open_page，避免误跳转。\n");
    prompt.append("7. 餐厅推荐：用户想找吃的/餐厅时，调用 search_restaurants，结合行程地点、口味与预算推荐 2-4 家，"
        + "并给出菜系、评分、人均消费、营业时间与地址；营业时间以工具返回为准。\n");
    prompt.append("8. 景点信息：查询景点营业时间/评分/地址时调用 search_attractions。"
        + "营业时间必须采用工具返回值；仅当工具未返回时才可凭常识给出，并注明「以景区公告为准」。\n");
    prompt.append("9. 酒店推荐（前置条件与流程，重要）：用户想找住的地方/酒店/民宿时，必须先确认行程的每一天都已经有景点/行程安排，才可调用 search_hotels 推荐。"
        + "工具返回 blocked 即表示条件未满足：此时绝对不要推荐或罗列任何酒店（包括凭常识推荐），"
        + "应简洁引导用户先确定缺失天的景点/行程（指出是第几天），并可主动提议「要我先帮你规划第 X 天吗？」；确认后再推荐。"
        + "满足条件后，流程与行程规划保持一致、一次到位：结合当天行程（尤其当天最后一个景点位置与次日出发路线）、评分与参考价格，"
        + "由你直接选定最合适的一家，立即调用 propose_itinerary_change 生成 type=hotel 的写入草稿"
        + "（change 项填全 name/address/latitude/longitude/startTime/endTime/notes），"
        + "不要先让用户点选、再等下一轮才生成草稿。"
        + "回复正文必须包含：① 选定酒店的完整介绍——评分、参考价格（工具未返回价格时按常识估计并注明「仅供参考」）、地址、电话（工具返回时给出）；"
        + "② 一句话选择理由（如离当晚景点/次日出发更近）；③ 其他候选酒店各一行（名称·评分·参考价·地址），"
        + "并说明可点下方「换住」按钮或直接说换哪家；"
        + "④ 用户选定某家并要求订下/登记时，调用 add_booking（type=hotel）生成登记草稿。不要使用 Markdown 表格，介绍用简短行文或「·」列表。\n");
    prompt.append("10. 时间规划（重要）：用户要求规划某一天的行程时，用 propose_itinerary_change 生成草稿（不要直接写行程）。要求："
        + "(a) 为每个景点给出合理停留时长——小型景点 1~1.5 小时、中型 2~3 小时、大型景区/博物馆 3~4 小时，"
        + "并用 24 小时制 startTime/endTime 标出；"
        + "(b) 相邻地点之间预留 20~40 分钟交通时间，并按地理远近合理安排顺序；"
        + "(c) 用餐时段（午 11:30–13:00、晚 17:30–19:00）安排餐厅，避免与景点冲突；"
        + "(d) 严格遵守景点营业时间，避开展馆闭馆日/闭馆时段（如博物馆多周一闭馆）；"
        + "(e) 每个景点在 change 项给出 estimatedCost（门票，人民币数字，免费填 0），并将营业时间放入 openTime；"
        + "(f) 一天不宜排得过满，一般不超过 6 个点；回复中给出该天的整体时间轴概览。\n");
    prompt.append("11. 票价：购物车/高德不返回门票价格，estimatedCost 由你按常识给出参考值，"
        + "并在回复中说明「票价与营业时间仅供参考，以景区/餐厅公示为准」。金额一律人民币。\n");
    prompt.append("12. 相对天数与默认行程（重要）：用户说「第二天」「第 N 天」时，一律指行程内的第 N 天（dayIndex=N，从 1 开始），"
        + "结合行程日期与现有安排换算成真实日期后据此规划，绝不理解为自然日历上的「明天」；"
        + "用户未指明行程时：对话已关联行程则用当前行程，全局对话则用下方「最近行程」上下文，"
        + "并在回复开头用一句话明示默认假设（须包含「默认」或「假设」字样，例如「默认按最近行程《赤峰之旅》（07-10~07-12），第二天即 07-11 为你规划」），"
        + "同时提示用户如需调整（换行程或换天数）可以随时说；"
        + "若该行程不存在第 N 天、或用户所指的行程不明确，先与用户确认，不要臆造日期。"
        + "全局对话中的记账等写入操作同样默认作用于最近行程，并在回复中说明这一点。\n");
    prompt.append("13. 预订登记（重要）：用户要求预订/登记/记录一条预订（如「订了机票 CA1234」「把这家酒店记下来」「门票已订好」），或选定酒店后要求订下/登记时，"
        + "必须调用 add_booking 生成待确认草稿，绝不直接写入；生成后向用户复述类型、标题、时间与金额，并说明在卡片点「应用」后即写入预定管理。"
        + "title 必填；时间不确定时可用行程内合理时间并注明假设；不确定是否已登记过时可先 get_bookings 查询避免重复。"
        + "用户只说「预订/订一下/预定某天的行程」而没指明具体对象（哪一天、哪一项、哪家酒店）时，先按规则 14 用 ask_options 让用户点选，再生成草稿，不要让用户手动输入。\n");
    prompt.append("14. 选择按钮（重要）：当需要用户从有限候选中做选择时（预订哪一天的哪一项、选择类型/日期、是否确认），"
        + "必须调用 ask_options 生成可点击按钮，不要让用户手动打字：options 必须基于工具返回的真实数据"
        + "（如 list_trip_days 的每天行程点或 search_hotels 的酒店），每条简短具体（≤16 字），2-6 条；"
        + "酒店/住宿候选的每条选项必须写成对象 {\"label\":\"订这家：××酒店\",\"description\":\"4.6 分 · ¥320 · 地址 ×× · 电话 ××\"}，"
        + "description 取自 search_hotels 的 rating/avgCost/address/tel（缺项省略），让用户能比较后再点选；其他场景选项用字符串即可。"
        + "调用后最终回复只写一句自然提问，不要重复罗列选项文本"
        + "（酒店推荐场景除外：按规则 9 正文必须给出选定酒店的完整介绍与其他候选清单）。"
        + "注意：本轮已经生成草稿卡片时，选择已由卡片承载，不要再调用 ask_options；"
        + "酒店推荐生成草稿后，可额外附一组「换住：××」按钮（同样带 description 介绍）方便用户一键换酒店。\n");
    prompt.append("15. 草稿必须真调用工具（重要）：历史消息里形如「【系统记录·历史草稿卡片（…），不是你的回复】」的内容是系统记录，"
        + "不是你说过的话，也绝不代表本轮已有草稿。用户每次要求规划/调整行程、记账、登记预订时，"
        + "你都必须在本轮实际调用对应工具（propose_itinerary_change / add_expense / add_booking）生成新草稿；"
        + "回复正文里出现「草稿已生成」「点卡片上的应用」之前，必须已经成功调用了工具。"
        + "禁止只用文字描述变更、或在正文里复述一句草稿摘要就声称卡片已生成——用户看不到任何卡片。\n");
    prompt.append("16. 整趟规划（重要）：用户要求一次性规划整趟行程/全程/所有天（如「帮我规划整个行程」「把这几天都安排好」「生成一份 N 日游攻略」）时，"
        + "不要一天一天反复对话，在同一轮内完成：\n"
        + "(a) 逐天规划、并行查询：一次消息里同时调用多个 search_places/search_attractions（每天一组），减少工具轮次；\n"
        + "(b) 查完后只调用一次 propose_itinerary_change：changes 数组包含全部各天的变更项，每项必须带 dayIndex、name、"
        + "latitude/longitude（来自搜索结果）、startTime/endTime、estimatedCost、openTime；"
        + "整趟重新规划时 replaceDay=true（会覆盖各天原有安排，须在回复中明确提醒用户）；"
        + "若行程已有部分安排、用户只想补全空白天，则只对空白天给出变更且 replaceDay=false；\n"
        + "(c) 每天安排 2-5 个景点/活动 + 对应用餐，遵守规则 10 的时长/交通/用餐/营业时间规范，按地理路线就近排序、避免来回折返；"
        + "相邻天之间顺路衔接（前一晚住在哪，次日上午就从附近开始）；\n"
        + "(d) 严格基于行程已有的目的地与天数规划，不要虚构行程没有的天数；行程还没有任何天时，先引导用户创建行程再规划；\n"
        + "(e) 用户给了偏好（亲子/穷游/自驾/摄影/美食等）时按偏好调整：自驾控制单日车程与路线顺路性，亲子减少每日点数并优先亲子友好场所，"
        + "穷游优先免费/低价景点，并在回复开头用一句话点明采纳的偏好；\n"
        + "(f) 行程超过 7 天时建议分两段（先前半段，确认后再规划后半段），避免单次内容过长；\n"
        + "(g) 最终回复给出逐天概览（每天一行：「第 N 天（日期）：A → B → C」），说明票价与营业时间仅供参考，"
        + "并告知点草稿卡片「应用」后会一次性写入全部天。\n");
    prompt.append("17. 天气参考（重要）：上下文与 get_weather 工具提供目的地每日预报（现象/最高最低温/降雨概率，"
        + "Open-Meteo 数据，仅覆盖未来日期，存在 6 小时缓存）。规划行程、推荐景点/餐厅/酒店时必须结合天气：\n"
        + "(a) 雨/雪/雷暴天优先安排博物馆、展馆、室内乐园等项目，或建议用户把户外景点与晴好天互换"
        + "（如「10-23 有雨，建议把户外景点换到 10-22，10-23 改逛博物馆」）；\n"
        + "(b) 温差大（≥12℃）或气温极端（≥35℃/≤-5℃）时提醒携带衣物/防晒/保暖；\n"
        + "(c) 规划草稿的 notes 里可注明当天天气与提醒；\n"
        + "(d) 预报未覆盖的日期（过去、或超出预报范围）无法预知天气，直接说明即可，绝不编造天气。\n");
    prompt.append("18. 偏好记忆（重要）：系统上下文中的「用户旅行偏好」是用户让 AI 长期记住的持久偏好，"
        + "所有推荐（餐厅/景点/酒店）、规划（单天/整趟）与回答都必须遵守——例如用户不吃辣就绝不推荐辣味餐厅，"
        + "并可在合适时自然提及「按您的偏好」让用户感到被记住。规则：\n"
        + "(a) 用户表达持久偏好（忌口、玩法喜好、预算档、住宿档次、节奏、同行人群等）时，"
        + "调用 remember_preference 保存，并用一句话简短确认（如「好的，已记住您不吃辣，之后推荐都会避开辣味」）；"
        + "内容已保存过时工具会返回 alreadyKnown=true，此时直接说「这条我已经记着啦」；\n"
        + "(b) 单次性/临时性要求（如「这次想去看海」）不要保存，本次遵守即可；\n"
        + "(c) 用户要求忘记/删除某条偏好时调用 forget_preference；用户问「我的偏好」时调用 list_preferences；\n"
        + "(d) 偏好与实际情况冲突时（如用户不吃辣但行程目的地以辣为主），如实提醒用户并提供替代方案。\n");
    try {
      List<String> preferenceLines = preferenceService.promptLines(conversation.getUserId());
      if (!preferenceLines.isEmpty()) {
        prompt.append("\n用户旅行偏好（持久记忆，推荐与规划必须遵守）：\n");
        for (int i = 0; i < preferenceLines.size(); i++) {
          prompt.append(i + 1).append(") ").append(preferenceLines.get(i)).append("\n");
        }
      }
    } catch (Exception ignore) {
      // 偏好读取失败不影响对话
    }
    if (conversation.getTripId() != null) {
      prompt.append("\n当前关联行程上下文：\n");
      prompt.append("当前行程 ID：").append(conversation.getTripId())
          .append("（调用工具时直接使用此 ID，无需向用户询问）。当前用户是本行程成员，可直接操作。\n");
      appendTripContext(prompt, conversation.getTripId(), conversation.getUserId());
    } else {
      prompt.append("\n当前对话未关联具体行程（在全局页面打开）。\n");
      String defaultTripId = resolveDefaultTripId(conversation.getUserId());
      if (defaultTripId != null) {
        prompt.append("最近行程 ID：").append(defaultTripId)
            .append("（默认引用对象；用户未指明行程时，行程变更/记账等操作一律以该行程为准，无需向用户询问 ID）。\n");
        appendTripContext(prompt, defaultTripId, conversation.getUserId());
        prompt.append("用户说「第二天」等相对天数时，指本行程的第 N 天（按上方各天日期换算），不是自然日历上的明天。\n");
        prompt.append("（全局对话规则）用户未指明行程时，回复开头第一句必须写明默认假设（包含「默认」或「假设」字样），")
            .append("格式如：「默认按最近行程《行程名》（开始~结束），第二天即 M月D日 为你规划，如需更换行程或天数可以告诉我」，再给出规划内容。\n");
      } else {
        prompt.append("该用户还没有行程，涉及行程操作时先引导其创建行程（可用 open_page 打开 trips 或 trip-form 页面）。\n");
      }
    }
    return prompt.toString();
  }

  public String buildPrompt(AiConversation conversation) {
    return buildSystemPrompt(conversation);
  }

  /**
   * 行程目的地逐日预报，按「MM-dd」（UTC，与行程天日期同口径）建索引，供 appendTripContext 内联注入。
   * WeatherService 自带 6 小时缓存与兜底（无数据返回空列表），任何异常都不影响提示词生成。
   */
  private Map<String, Map<String, Object>> loadWeatherByDate(String tripId, String userId) {
    Map<String, Map<String, Object>> byDate = new java.util.LinkedHashMap<>();
    try {
      SimpleDateFormat keyFormat = new SimpleDateFormat("MM-dd");
      keyFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      Map<String, Object> res = weatherService.getTripWeather(tripId, userId);
      if (res.get("weather") instanceof List<?> rows) {
        for (Object row : rows) {
          if (row instanceof Map<?, ?> map && map.get("date") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> weather = (Map<String, Object>) map;
            byDate.putIfAbsent(keyFormat.format((Date) map.get("date")), weather);
          }
        }
      }
    } catch (Exception ignore) {
      // 天气不可用时不注入，不影响规划主流程
    }
    return byDate;
  }

  /** 行程基础信息 + 逐天现有安排（内联当日天气），供系统提示词复用。 */
  private void appendTripContext(StringBuilder prompt, String tripId, String userId) {
    try {
      Map<String, Object> trip = tripService.findOne(tripId, userId);
      @SuppressWarnings("unchecked")
      Map<String, Object> tripMap = (Map<String, Object>) trip.get("trip");
      if (tripMap != null) {
        prompt.append("行程：").append(tripMap.get("title"))
            .append("，目的地：").append(tripMap.get("destination"))
            .append("，日期：").append(tripMap.get("startDate")).append(" ~ ")
            .append(tripMap.get("endDate")).append("，总预算：")
            .append(tripMap.get("budget") == null ? "未设" : tripMap.get("budget")).append("。\n");
      }
      Map<String, Object> days = tripDayService.findAll(tripId, userId);
      if (days.get("days") instanceof List<?> dayList) {
        prompt.append("现有行程安排：\n");
        SimpleDateFormat format = new SimpleDateFormat("MM-dd");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        Map<String, Map<String, Object>> weatherByDate = loadWeatherByDate(tripId, userId);
        List<Integer> emptyDays = new ArrayList<>();
        for (Object item : dayList) {
          @SuppressWarnings("unchecked")
          Map<String, Object> day = (Map<String, Object>) item;
          prompt.append("第 ").append(day.get("dayIndex")).append(" 天（")
              .append(format.format(day.get("date"))).append("）：");
          Object dayDate = day.get("date");
          Map<String, Object> weather = dayDate == null ? null
              : weatherByDate.get(format.format(dayDate));
          if (weather != null) {
            prompt.append("【天气：").append(weather.get("condition"));
            if (weather.get("tempLow") != null && weather.get("tempHigh") != null) {
              prompt.append(" ").append(weather.get("tempLow")).append("~")
                  .append(weather.get("tempHigh")).append("℃");
            }
            if (weather.get("rainChance") != null) {
              prompt.append("，降雨概率 ").append(weather.get("rainChance")).append("%");
            }
            prompt.append("】：");
          }
          if (day.get("places") instanceof List<?> places && !places.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object placeItem : places) {
              if (placeItem instanceof Map<?, ?> place) {
                names.add(String.valueOf(place.get("name")));
              }
            }
            prompt.append(String.join("、", names));
          } else {
            prompt.append("（暂无行程点）");
            emptyDays.add(day.get("dayIndex") instanceof Number number ? number.intValue() : 0);
          }
          prompt.append("\n");
        }
        List<String> emptyLabels = new ArrayList<>();
        for (int dayNumber : emptyDays) {
          if (dayNumber > 0) {
            emptyLabels.add(String.valueOf(dayNumber));
          }
        }
        if (!emptyLabels.isEmpty()) {
          prompt.append("注意：第 ").append(String.join("、", emptyLabels))
              .append(" 天还没有任何行程点；在行程每天都有景点/安排之前，不要推荐酒店，先引导用户补齐这些天的行程。\n");
        }
      }
    } catch (Exception ex) {
      prompt.append("（行程上下文读取失败，可请用户重新打开助手）\n");
    }
  }

  /**
   * 全局对话的默认行程：按「我的行程」排序（updatedAt 倒序）取最近一次**已规划**
   * （某天已有行程点）的行程；其次是最近有天的行程；再次则回退为最新行程。
   */
  public String resolveDefaultTripId(String userId) {
    try {
      Map<String, Object> res = tripService.findAll(userId);
      if (!(res.get("trips") instanceof List<?> trips) || trips.isEmpty()) {
        return null;
      }
      String firstId = null;
      String firstWithDays = null;
      int examined = 0;
      for (Object item : trips) {
        @SuppressWarnings("unchecked")
        Map<String, Object> tripMap = (Map<String, Object>) item;
        Object idObj = tripMap.get("id");
        if (idObj == null) {
          continue;
        }
        String id = idObj.toString();
        if (firstId == null) {
          firstId = id;
        }
        if (++examined > 10) {
          break;
        }
        try {
          Map<String, Object> daysRes = tripDayService.findAll(id, userId);
          if (!(daysRes.get("days") instanceof List<?> days) || days.isEmpty()) {
            continue;
          }
          if (firstWithDays == null) {
            firstWithDays = id;
          }
          for (Object dayObj : days) {
            if (dayObj instanceof Map<?, ?> day
                && day.get("places") instanceof List<?> places && !places.isEmpty()) {
              return id;
            }
          }
        } catch (Exception ignore) {
          // 单条读取失败则继续尝试下一条
        }
      }
      return firstWithDays != null ? firstWithDays : firstId;
    } catch (Exception ignore) {
      // 读取失败视为没有默认行程
    }
    return null;
  }
}
