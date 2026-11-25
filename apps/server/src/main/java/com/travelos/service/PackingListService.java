package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.Payloads;
import com.travelos.common.ApiException;
import com.travelos.entity.PackingItem;
import com.travelos.entity.PackingList;
import com.travelos.mapper.PackingItemMapper;
import com.travelos.mapper.PackingListMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PackingListService {

  private final PackingListMapper listMapper;
  private final PackingItemMapper itemMapper;
  private final TripMemberService tripMemberService;
  private final AuditService auditService;

  public PackingListService(PackingListMapper listMapper, PackingItemMapper itemMapper,
      TripMemberService tripMemberService, AuditService auditService) {
    this.listMapper = listMapper;
    this.itemMapper = itemMapper;
    this.tripMemberService = tripMemberService;
    this.auditService = auditService;
  }

  public Map<String, Object> findAll(String tripId, String userId) {
    requireTripMember(tripId, userId);
    List<Map<String, Object>> lists = findListsByTrip(tripId).stream()
        .map(this::toListResponse)
        .toList();
    return Map.of("packingLists", lists);
  }

  @Transactional
  public Map<String, Object> create(String tripId, String userId, Map<String, Object> dto) {
    String category = requiredString(dto.get("category"), "category should not be empty");
    PackingList list = insertList(tripId, userId, Payloads.text(dto.get("name")), category);
    for (Map.Entry<Integer, TemplateItem> entry : templateItems(category).entrySet()) {
      insertItem(list.getId(), entry.getValue().name(), entry.getValue().quantity(), null, entry.getKey());
    }
    return Map.of("packingList", toListResponse(findListById(list.getId())));
  }

  private PackingList insertList(String tripId, String userId, String name, String category) {
    PackingList list = new PackingList();
    list.setTripId(tripId);
    list.setName(normalizeName(name, 100, "清单名称"));
    list.setCategory(category);
    // 创建者以当前登录用户为准，禁止信任前端传入的 creatorId 等字段
    list.setCreatorId(userId);
    list.setCreatedAt(new Date());
    list.setUpdatedAt(new Date());
    listMapper.insert(list);
    return list;
  }

  @Transactional
  public Map<String, Object> update(String tripId, String listId, Map<String, Object> dto,
      String userId) {
    PackingList list = requireLockedList(tripId, listId);
    if (dto.containsKey("name")) {
      list.setName(normalizeName(Payloads.text(dto.get("name")), 100, "清单名称"));
    }
    if (dto.containsKey("category") && dto.get("category") != null) {
      list.setCategory(dto.get("category").toString());
    }
    list.setUpdatedAt(new Date());
    listMapper.updateById(list);
    return Map.of("packingList", toListResponse(findListById(listId)));
  }

  @Transactional
  public Map<String, Object> delete(String tripId, String listId, String userId) {
    PackingList list = requireLockedList(tripId, listId);
    // 数据隔离：仅创建者本人或行程 owner/admin 可删除整张清单（NULL 属主为存量兼容）
    tripMemberService.requireOwnerOrManager(tripId, list.getCreatorId(), userId, "清单创建者");
    itemMapper.delete(new LambdaQueryWrapper<PackingItem>()
        .eq(PackingItem::getPackingListId, listId));
    listMapper.deleteById(listId);
    auditService.record(userId, tripId, "packing_list_deleted", "packing_list", listId,
        Map.of("name", list.getName()));
    return Map.of("success", true);
  }

  @Transactional
  public Map<String, Object> createItem(String tripId, String listId, Map<String, Object> dto,
      String userId) {
    requireLockedList(tripId, listId);
    int quantity = 1;
    if (dto.containsKey("quantity") && dto.get("quantity") != null) {
      quantity = Integer.parseInt(dto.get("quantity").toString());
      if (quantity < 1) {
        throw ApiException.badRequest("物品数量必须大于等于 1");
      }
    }
    String name = normalizeName(requiredString(dto.get("name"), "name should not be empty"), 200, "物品名称");
    List<PackingItem> existing = itemsOf(listId);
    requireUniqueName(existing, name);
    int order = dto.get("orderIndex") == null ? existing.size() : Integer.parseInt(dto.get("orderIndex").toString());
    return Map.of("item", toItemResponse(insertItem(listId, name, quantity, Payloads.trimmedOrNull(dto.get("notes")), order)));
  }

  private PackingItem insertItem(String listId, String name, int quantity, String notes, int order) {
    PackingItem item = new PackingItem();
    item.setPackingListId(listId);
    item.setName(name);
    item.setQuantity(quantity);
    item.setIsPacked(false);
    item.setNotes(notes);
    item.setOrderIndex(order);
    itemMapper.insert(item);
    return item;
  }

  @Transactional
  public Map<String, Object> updateItem(String tripId, String listId, String itemId,
      Map<String, Object> dto, String userId) {
    requireLockedList(tripId, listId);
    PackingItem item = requireItem(listId, itemId);
    if (dto.containsKey("quantity") && dto.get("quantity") != null) {
      int quantity = Integer.parseInt(dto.get("quantity").toString());
      if (quantity < 1) {
        throw ApiException.badRequest("物品数量必须大于等于 1");
      }
      item.setQuantity(quantity);
    }
    if (dto.containsKey("name") && dto.get("name") != null) {
      String name = normalizeName(Payloads.text(dto.get("name")), 200, "物品名称");
      if (!nameKey(name).equals(nameKey(item.getName()))) {
        requireUniqueName(itemsOf(listId), name);
      }
      item.setName(name);
    }
    if (dto.containsKey("isPacked") && dto.get("isPacked") != null) {
      item.setIsPacked(Boolean.parseBoolean(dto.get("isPacked").toString()));
    }
    if (dto.containsKey("notes")) {
      item.setNotes(Payloads.trimmedOrNull(dto.get("notes")));
    }
    if (dto.containsKey("orderIndex") && dto.get("orderIndex") != null) {
      item.setOrderIndex(Integer.parseInt(dto.get("orderIndex").toString()));
    }
    itemMapper.updateById(item);
    return Map.of("item", toItemResponse(item));
  }

  @Transactional
  public Map<String, Object> deleteItem(String tripId, String listId, String itemId,
      String userId) {
    requireLockedList(tripId, listId);
    requireItem(listId, itemId);
    itemMapper.deleteById(itemId);
    return Map.of("success", true);
  }

  @Transactional
  public Map<String, Object> duplicate(String tripId, String listId, String userId) {
    PackingList source = requireList(tripId, listId);
    String suffix = " 副本";
    String newName = source.getName().length() + suffix.length() > 100
        ? source.getName().substring(0, 100 - suffix.length()) + suffix
        : source.getName() + suffix;

    PackingList list = new PackingList();
    list.setTripId(tripId);
    list.setName(newName);
    list.setCategory(source.getCategory());
    list.setCreatedAt(new Date());
    list.setUpdatedAt(new Date());
    listMapper.insert(list);
    for (PackingItem item : itemsOf(listId)) {
      PackingItem copy = new PackingItem();
      copy.setPackingListId(list.getId());
      copy.setName(item.getName());
      copy.setQuantity(item.getQuantity());
      copy.setIsPacked(item.getIsPacked());
      copy.setNotes(item.getNotes());
      copy.setOrderIndex(item.getOrderIndex());
      itemMapper.insert(copy);
    }
    return Map.of("packingList", toListResponse(findListById(list.getId())));
  }

  /** 调用方已锁定旅行和成员。清单锁与手工新增/改名物品共用。 */
  PackingInsert appendDraftItems(String tripId, String userId, String listId, String listName,
      List<PackingEntry> entries) {
    PackingList list;
    if (listId == null) {
      if (findListsByTrip(tripId).stream().anyMatch(existing -> nameKey(existing.getName()).equals(nameKey(listName)))) {
        throw ApiException.conflict("已有同名清单，请换个名称或重新生成草稿后选择已有清单");
      }
      list = insertList(tripId, userId, listName, "other");
    } else {
      list = requireLockedList(tripId, listId);
      if (!list.getName().equals(listName)) {
        throw ApiException.conflict("目标清单已改名，请重新生成打包草稿");
      }
    }
    List<PackingItem> current = itemsOf(list.getId());
    Set<String> keys = new HashSet<>();
    current.forEach(item -> keys.add(nameKey(item.getName())));
    List<PackingEntry> added = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (PackingEntry entry : entries) {
      if (keys.add(nameKey(entry.name()))) {
        insertItem(list.getId(), entry.name(), entry.quantity(), null, current.size() + added.size());
        added.add(entry);
      } else {
        skipped.add(entry.name());
      }
    }
    return new PackingInsert(list.getId(), list.getName(), added, skipped);
  }

  static String nameKey(String name) {
    return Normalizer.normalize(name, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ")
        .trim().toLowerCase(Locale.ROOT);
  }

  private void requireUniqueName(List<PackingItem> items, String name) {
    String key = nameKey(name);
    if (items.stream().anyMatch(item -> key.equals(nameKey(item.getName())))) {
      throw ApiException.conflict("清单中已有同名物品，请修改已有物品的数量");
    }
  }

  record PackingEntry(String name, int quantity) {}
  record PackingInsert(String listId, String listName, List<PackingEntry> added, List<String> skipped) {}

  List<PackingList> findListsByTrip(String tripId) {
    return listMapper.selectList(new LambdaQueryWrapper<PackingList>()
        .eq(PackingList::getTripId, tripId)
        .orderByAsc(PackingList::getCreatedAt));
  }

  private PackingList findListById(String listId) {
    return listMapper.selectById(listId);
  }

  List<PackingItem> itemsOf(String listId) {
    return itemMapper.selectList(new LambdaQueryWrapper<PackingItem>()
        .eq(PackingItem::getPackingListId, listId)
        .orderByAsc(PackingItem::getOrderIndex));
  }

  private Map<String, Object> toListResponse(PackingList list) {
    List<PackingItem> items = itemsOf(list.getId());
    long packedCount = items.stream().filter(item -> Boolean.TRUE.equals(item.getIsPacked())).count();
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", list.getId());
    map.put("tripId", list.getTripId());
    map.put("name", list.getName());
    map.put("category", list.getCategory());
    map.put("packedCount", packedCount);
    map.put("itemCount", items.size());
    map.put("createdAt", list.getCreatedAt());
    map.put("updatedAt", list.getUpdatedAt());
    map.put("items", items.stream().map(this::toItemResponse).toList());
    return map;
  }

  private Map<String, Object> toItemResponse(PackingItem item) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", item.getId());
    map.put("packingListId", item.getPackingListId());
    map.put("name", item.getName());
    map.put("quantity", item.getQuantity());
    map.put("isPacked", item.getIsPacked());
    map.put("notes", item.getNotes());
    map.put("orderIndex", item.getOrderIndex());
    return map;
  }

  private void requireTripMember(String tripId, String userId) {
    try {
      tripMemberService.requireTripMember(tripId, userId);
    } catch (ApiException ex) {
      if (ex.getStatusCode() == 404) {
        throw ApiException.forbidden("没有权限访问该旅行的打包清单");
      }
      throw ex;
    }
  }

  private PackingList requireList(String tripId, String listId) {
    PackingList list = findListById(listId);
    if (list == null || !list.getTripId().equals(tripId)) {
      throw ApiException.notFound("打包清单不存在");
    }
    return list;
  }

  private PackingList requireLockedList(String tripId, String listId) {
    PackingList list = listMapper.selectOne(new LambdaQueryWrapper<PackingList>()
        .eq(PackingList::getTripId, tripId).eq(PackingList::getId, listId).last("FOR UPDATE"));
    if (list == null) {
      throw ApiException.notFound("打包清单不存在");
    }
    return list;
  }

  private PackingItem requireItem(String listId, String itemId) {
    PackingItem item = itemMapper.selectById(itemId);
    if (item == null || !item.getPackingListId().equals(listId)) {
      throw ApiException.notFound("打包物品不存在");
    }
    return item;
  }

  private String normalizeName(String value, int maxLength, String label) {
    String name = value == null ? "" : value.trim();
    if (name.isEmpty() || name.length() > maxLength) {
      throw ApiException.badRequest(label + "长度必须为 1-" + maxLength + " 个字符");
    }
    return name;
  }

  private String requiredString(Object value, String message) {
    if (value == null || value.toString().isBlank()) {
      throw ApiException.badRequest(message);
    }
    return value.toString();
  }

  private Map<Integer, TemplateItem> templateItems(String category) {
    List<TemplateItem> items = switch (category) {
      case "clothing" -> List.of(new TemplateItem("上衣", 3), new TemplateItem("裤子", 2),
          new TemplateItem("内衣", 4), new TemplateItem("袜子", 4));
      case "toiletries" -> List.of(new TemplateItem("牙刷", 1), new TemplateItem("牙膏", 1),
          new TemplateItem("洗面奶", 1), new TemplateItem("毛巾", 1));
      case "electronics" -> List.of(new TemplateItem("手机充电器", 1), new TemplateItem("充电宝", 1),
          new TemplateItem("转换插头", 1));
      case "medicine" -> List.of(new TemplateItem("常用药", 1), new TemplateItem("创可贴", 5));
      case "documents" -> List.of(new TemplateItem("身份证件", 1), new TemplateItem("行程单", 1));
      default -> List.of(new TemplateItem("雨伞", 1), new TemplateItem("水杯", 1));
    };
    Map<Integer, TemplateItem> map = new LinkedHashMap<>();
    for (int index = 0; index < items.size(); index++) {
      map.put(index, items.get(index));
    }
    return map;
  }

  private record TemplateItem(String name, int quantity) {
  }
}
