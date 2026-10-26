package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.entity.AiPreference;
import com.travelos.mapper.AiPreferenceMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 旅行偏好记忆：AI 助手在对话中记住/忘记用户的持久旅行偏好，
 * 并在系统提示词中注入（推荐与规划时遵守）。内容为用户原话的短句归一化。
 */
@Service
public class AiPreferenceService {

  private static final int MAX_ACTIVE = 20;
  private static final int MAX_CONTENT_LENGTH = 60;

  private final AiPreferenceMapper preferenceMapper;

  public AiPreferenceService(AiPreferenceMapper preferenceMapper) {
    this.preferenceMapper = preferenceMapper;
  }

  /** 记住一条偏好（归一化去重；超过上限时移除最早的记录）。 */
  public Map<String, Object> remember(String userId, String content, String category) {
    String normalized = normalize(content);
    if (normalized.isEmpty()) {
      throw ApiException.badRequest("偏好内容不能为空");
    }
    if (normalized.length() > MAX_CONTENT_LENGTH) {
      normalized = normalized.substring(0, MAX_CONTENT_LENGTH);
    }
    String cat = normalizeCategory(category);
    AiPreference existing = findByContent(userId, normalized);
    if (existing != null) {
      // 同内容已存在：仅更新分类与时间，视为成功（不重复入库）
      existing.setCategory(cat);
      existing.setUpdatedAt(new Date());
      preferenceMapper.updateById(existing);
      return Map.of("saved", true, "alreadyKnown", true,
          "preference", toMap(existing));
    }
    List<AiPreference> actives = listActive(userId);
    if (actives.size() >= MAX_ACTIVE) {
      AiPreference oldest = actives.get(0);
      oldest.setStatus("removed");
      oldest.setUpdatedAt(new Date());
      preferenceMapper.updateById(oldest);
    }
    AiPreference preference = new AiPreference();
    preference.setUserId(userId);
    preference.setContent(normalized);
    preference.setCategory(cat);
    preference.setStatus("active");
    preference.setCreatedAt(new Date());
    preference.setUpdatedAt(new Date());
    preferenceMapper.insert(preference);
    return Map.of("saved", true, "alreadyKnown", false, "preference", toMap(preference));
  }

  /** 忘记偏好：按 id 或内容关键词（模糊匹配活动偏好，命中多条则全部移除）。 */
  public Map<String, Object> forget(String userId, String keyword) {
    String normalized = keyword == null ? "" : keyword.trim();
    if (normalized.isEmpty()) {
      throw ApiException.badRequest("请提供要忘记的偏好内容或 ID");
    }
    int removed = 0;
    List<AiPreference> targets;
    if (normalized.matches("\\d{10,}")) {
      AiPreference byId = preferenceMapper.selectById(normalized);
      targets = byId == null ? List.of() : List.of(byId);
    } else {
      targets = listActive(userId).stream()
          .filter(p -> p.getContent().contains(normalized)
              || normalized.contains(p.getContent()))
          .toList();
    }
    Date now = new Date();
    for (AiPreference preference : targets) {
      preference.setStatus("removed");
      preference.setUpdatedAt(now);
      preferenceMapper.updateById(preference);
      removed++;
    }
    return Map.of("removed", removed,
        "message", removed == 0 ? "没有找到匹配的偏好" : "已忘记 " + removed + " 条偏好");
  }

  /** 活动偏好列表（AI 工具展示用）。 */
  public List<Map<String, Object>> list(String userId) {
    return listActive(userId).stream().map(this::toMap).toList();
  }

  /** 供系统提示词注入的偏好行（按创建时间正序，最多 MAX_ACTIVE 条）。 */
  public List<String> promptLines(String userId) {
    return listActive(userId).stream()
        .map(p -> p.getContent() + (p.getCategory() == null
            || "general".equals(p.getCategory()) ? "" : "（" + categoryLabel(p.getCategory()) + "）"))
        .toList();
  }

  private List<AiPreference> listActive(String userId) {
    return preferenceMapper.selectList(new LambdaQueryWrapper<AiPreference>()
        .eq(AiPreference::getUserId, userId)
        .eq(AiPreference::getStatus, "active")
        .orderByAsc(AiPreference::getCreatedAt)
        .last("LIMIT " + MAX_ACTIVE));
  }

  private AiPreference findByContent(String userId, String normalized) {
    List<AiPreference> matches = preferenceMapper.selectList(
        new LambdaQueryWrapper<AiPreference>()
            .eq(AiPreference::getUserId, userId)
            .eq(AiPreference::getStatus, "active")
            .apply("lower(content) = {0}", normalized.toLowerCase()));
    return matches.isEmpty() ? null : matches.get(0);
  }

  private String normalize(String content) {
    return content == null ? "" : content.trim()
        .replaceAll("^[「『\"']+", "")
        .replaceAll("[」』\"']+$", "")
        .replaceAll("^(记住|请记住|记一下|我的偏好[是：:]|偏好[是：:])", "")
        .trim();
  }

  private String normalizeCategory(String category) {
    String cat = category == null ? "" : category.trim().toLowerCase();
    return switch (cat) {
      case "food", "activity", "budget", "accommodation", "pace", "companion", "general" -> cat;
      default -> "general";
    };
  }

  private String categoryLabel(String category) {
    return switch (category) {
      case "food" -> "饮食";
      case "activity" -> "玩法";
      case "budget" -> "预算";
      case "accommodation" -> "住宿";
      case "pace" -> "节奏";
      case "companion" -> "同行";
      default -> "偏好";
    };
  }

  private Map<String, Object> toMap(AiPreference preference) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", preference.getId());
    map.put("content", preference.getContent());
    map.put("category", preference.getCategory());
    map.put("updatedAt", preference.getUpdatedAt());
    return map;
  }
}
