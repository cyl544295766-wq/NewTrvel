package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.entity.PackingItem;
import com.travelos.entity.PackingList;
import com.travelos.entity.TripDay;
import com.travelos.mapper.PackingItemMapper;
import com.travelos.mapper.PackingListMapper;
import com.travelos.mapper.TripDayMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程模板（P0-6）：按出行类型一键生成整套打包清单（多分类 + 预填物品），
 * 并为已生成、未命名的行程天套用建议标题。内置模板常量化，不落库、无迁移。
 */
@Service
public class TripTemplateService {

  /** 模板中的一个清单：category 沿用打包模块的类别编码。 */
  public record TplList(String category, String name, List<String> items) {
  }

  public record Template(String id, String name, String desc,
      List<String> dayTitles, List<TplList> lists) {
  }

  private static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();

  static {
    TEMPLATES.put("island", new Template("island", "\u6d77\u5c9b\u5ea6\u5047",
        "\u6d6e\u6f5c\u3001\u6c99\u6ee9\u4e0e\u9633\u5149\u7684\u6574\u5957\u884c\u56ca",
        List.of("\u62b5\u8fbe \u00b7 \u5165\u4f4f\u9152\u5e97", "\u51fa\u6d77\u6d6e\u6f5c", "\u73af\u5c9b\u6162\u6e38",
            "\u81ea\u7531\u6d3b\u52a8 / \u8d2d\u7269", "\u8fd4\u7a0b"),
        List.of(
            new TplList("clothing", "\u8863\u7269", List.of("\u6cf3\u8863", "\u6c99\u6ee9\u88e4 / \u6c99\u6ee9\u88d9",
                "\u9632\u6652\u8863", "\u906e\u9633\u5e3d", "\u6362\u6d17\u8863\u7269", "\u6c99\u6ee9\u978b")),
            new TplList("toiletries", "\u6d17\u6f31", List.of("\u9632\u6652\u971c SPF50", "\u82a6\u835f\u80f6",
                "\u6d17\u6f31\u5305")),
            new TplList("electronics", "\u7535\u5b50", List.of("\u9632\u6c34\u624b\u673a\u888b", "\u5145\u7535\u5b9d",
                "\u76f8\u673a")),
            new TplList("documents", "\u8bc1\u4ef6", List.of("\u8eab\u4efd\u8bc1", "\u62a4\u7167", "\u4fdd\u9669\u5355")),
            new TplList("other", "\u5176\u4ed6", List.of("\u58a8\u955c", "\u9632\u6c34\u888b", "\u6c99\u6ee9\u5dfe"))
        )));
    TEMPLATES.put("city", new Template("city", "\u57ce\u5e02\u5468\u672b",
        "\u8f7b\u88c5\u51fa\u53d1\u7684\u4e24\u65e5\u6162\u6e38",
        List.of("\u62b5\u8fbe \u00b7 \u8001\u57ce\u6f2b\u6b65", "\u535a\u7269\u9986\u4e0e\u7f8e\u98df", "\u8fd4\u7a0b"),
        List.of(
            new TplList("clothing", "\u8863\u7269", List.of("\u6362\u6d17\u8863\u7269", "\u8212\u9002\u6b65\u884c\u978b",
                "\u8f7b\u8584\u5916\u5957")),
            new TplList("toiletries", "\u6d17\u6f31", List.of("\u6d17\u6f31\u5305")),
            new TplList("electronics", "\u7535\u5b50", List.of("\u5145\u7535\u5b9d", "\u5145\u7535\u7ebf")),
            new TplList("documents", "\u8bc1\u4ef6", List.of("\u8eab\u4efd\u8bc1", "\u4ea4\u901a\u5361")),
            new TplList("other", "\u5176\u4ed6", List.of("\u96e8\u4f1e", "\u6c34\u676f"))
        )));
    TEMPLATES.put("hike", new Template("hike", "\u96ea\u5c71\u5f92\u6b65",
        "\u4f4e\u6e29\u4e0e\u9ad8\u6d77\u62d4\u7684\u88c5\u5907\u6e05\u5355",
        List.of("\u62b5\u8fbe \u00b7 \u9002\u5e94\u6d77\u62d4", "\u5927\u672c\u8425\u5f92\u6b65", "\u96ea\u5c71\u51b2\u9876",
            "\u8fd4\u7a0b"),
        List.of(
            new TplList("clothing", "\u8863\u7269", List.of("\u51b2\u950b\u8863", "\u6293\u7ed2\u8863",
                "\u767b\u5c71\u889c", "\u4fdd\u6696\u624b\u5957", "\u6bdb\u7ebf\u5e3d")),
            new TplList("toiletries", "\u6d17\u6f31", List.of("\u6d17\u6f31\u5305", "\u9632\u6652\u971c", "\u6da6\u5507\u818f")),
            new TplList("electronics", "\u7535\u5b50", List.of("\u5145\u7535\u5b9d", "\u5934\u706f")),
            new TplList("medicine", "\u836f\u54c1", List.of("\u521b\u53ef\u8d34", "\u611f\u5192\u836f",
                "\u9ad8\u53cd\u836f", "\u6b62\u75db\u836f")),
            new TplList("documents", "\u8bc1\u4ef6", List.of("\u8eab\u4efd\u8bc1", "\u4fdd\u9669\u5355")),
            new TplList("other", "\u5176\u4ed6", List.of("\u767b\u5c71\u6756", "\u4fdd\u6e29\u676f",
                "\u80fd\u91cf\u68d2", "\u58a8\u955c"))
        )));
    TEMPLATES.put("business", new Template("business", "\u5546\u52a1\u51fa\u5dee",
        "\u4f1a\u8bae\u4e0e\u62dc\u8bbf\u7684\u5229\u843d\u884c\u56ca",
        List.of("\u62b5\u8fbe \u00b7 \u4f1a\u524d\u51c6\u5907", "\u4f1a\u8bae\u65e5", "\u5ba2\u6237\u62dc\u8bbf",
            "\u8fd4\u7a0b"),
        List.of(
            new TplList("clothing", "\u8863\u7269", List.of("\u6b63\u88c5", "\u6362\u6d17\u8863\u7269",
                "\u76ae\u978b")),
            new TplList("toiletries", "\u6d17\u6f31", List.of("\u6d17\u6f31\u5305", "\u5243\u987b\u5200")),
            new TplList("electronics", "\u7535\u5b50", List.of("\u7b14\u8bb0\u672c\u7535\u8111",
                "\u7535\u8111\u5145\u7535\u5668", "\u5145\u7535\u5b9d")),
            new TplList("documents", "\u8bc1\u4ef6", List.of("\u8eab\u4efd\u8bc1", "\u62a4\u7167",
                "\u884c\u7a0b\u5355")),
            new TplList("other", "\u5176\u4ed6", List.of("\u540d\u7247", "U \u76d8"))
        )));
  }

  private final TripMemberService tripMemberService;
  private final PackingListMapper packingListMapper;
  private final PackingItemMapper packingItemMapper;
  private final TripDayMapper tripDayMapper;
  private final AuditService auditService;

  public TripTemplateService(TripMemberService tripMemberService,
      PackingListMapper packingListMapper, PackingItemMapper packingItemMapper,
      TripDayMapper tripDayMapper, AuditService auditService) {
    this.tripMemberService = tripMemberService;
    this.packingListMapper = packingListMapper;
    this.packingItemMapper = packingItemMapper;
    this.tripDayMapper = tripDayMapper;
    this.auditService = auditService;
  }

  public List<Map<String, Object>> list() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Template t : TEMPLATES.values()) {
      result.add(Map.of(
          "id", t.id(),
          "name", t.name(),
          "desc", t.desc(),
          "listCount", t.lists().size(),
          "itemCount", t.lists().stream().mapToInt(l -> l.items().size()).sum()));
    }
    return result;
  }

  /**
   * 应用模板：生成整套打包清单（同名清单可重复生成，模板不覆盖既有内容），
   * 并为未命名的行程天套用建议标题（按天序号对位）。
   */
  @Transactional
  public Map<String, Object> apply(String tripId, String userId, String templateId) {
    tripMemberService.requireTripMember(tripId, userId);
    Template template = TEMPLATES.get(templateId == null ? "" : templateId.trim());
    if (template == null) {
      throw ApiException.badRequest("\u672a\u77e5\u6a21\u677f\uff1a" + templateId);
    }

    int listsCreated = 0;
    int itemsCreated = 0;
    for (TplList def : template.lists()) {
      PackingList list = new PackingList();
      list.setTripId(tripId);
      list.setName(def.name());
      list.setCategory(def.category());
      list.setCreatorId(userId);
      list.setCreatedAt(new Date());
      list.setUpdatedAt(new Date());
      packingListMapper.insert(list);
      listsCreated++;
      int order = 0;
      for (String itemName : def.items()) {
        PackingItem item = new PackingItem();
        item.setPackingListId(list.getId());
        item.setName(itemName);
        item.setQuantity(1);
        item.setIsPacked(false);
        item.setOrderIndex(order++);
        packingItemMapper.insert(item);
        itemsCreated++;
      }
    }

    List<TripDay> days = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId)
        .orderByAsc(TripDay::getDayIndex));
    List<String> titles = template.dayTitles();
    int daysTitled = 0;
    for (int i = 0; i < days.size() && i < titles.size(); i++) {
      TripDay day = days.get(i);
      if (day.getTitle() != null && !day.getTitle().isBlank()) {
        continue;
      }
      day.setTitle(titles.get(i));
      day.setUpdatedAt(new Date());
      tripDayMapper.updateById(day);
      daysTitled++;
    }

    auditService.record(userId, tripId, "template_applied", "trip", tripId,
        Map.of("template", template.id()));
    return Map.of(
        "success", true,
        "template", template.name(),
        "listsCreated", listsCreated,
        "itemsCreated", itemsCreated,
        "daysTitled", daysTitled);
  }
}
