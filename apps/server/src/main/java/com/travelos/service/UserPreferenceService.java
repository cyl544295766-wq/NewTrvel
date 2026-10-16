package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.entity.UserPreference;
import com.travelos.mapper.UserPreferenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 用户偏好设置：账户页「偏好与显示」的读写。
 * 每用户一行，首次 GET 时按默认值懒创建；PUT 只更新传入的字段（null = 保持不变）。
 */
@Service
public class UserPreferenceService {

  private static final Set<String> THEMES = Set.of("system", "light", "dark");
  private static final Set<String> TIMEZONES = Set.of("Asia/Shanghai", "Asia/Tokyo", "UTC");
  private static final Set<String> DISTANCE_UNITS = Set.of("km", "mi");
  private static final Set<String> CURRENCIES = Set.of("CNY", "USD", "EUR");
  private static final Set<String> DATE_FORMATS = Set.of("cn", "slash", "us");
  private static final Set<String> WEEK_STARTS = Set.of("mon", "sun");

  private final UserPreferenceMapper mapper;

  public UserPreferenceService(UserPreferenceMapper mapper) {
    this.mapper = mapper;
  }

  /** 读取（不存在则按默认值创建；插入后回读，让 DB 默认值反映到响应）。 */
  @Transactional
  public Map<String, Object> get(UserContext currentUser) {
    UserPreference pref = findByUser(currentUser.id());
    if (pref == null) {
      pref = new UserPreference();
      pref.setUserId(currentUser.id());
      mapper.insert(pref);
      pref = findByUser(currentUser.id());
    }
    return Map.of("preferences", toResponse(pref));
  }

  /** 部分更新：字段为 null 表示保持不变；枚举值不合法直接 400。 */
  @Transactional
  public Map<String, Object> update(UserContext currentUser, UpdateRequest req) {
    UserPreference pref = findByUser(currentUser.id());
    if (pref == null) {
      pref = new UserPreference();
      pref.setUserId(currentUser.id());
    }
    if (req.theme() != null) {
      require(THEMES, "主题", req.theme());
      pref.setTheme(req.theme());
    }
    if (req.timezone() != null) {
      require(TIMEZONES, "时区", req.timezone());
      pref.setTimezone(req.timezone());
    }
    if (req.distanceUnit() != null) {
      require(DISTANCE_UNITS, "距离单位", req.distanceUnit());
      pref.setDistanceUnit(req.distanceUnit());
    }
    if (req.currency() != null) {
      require(CURRENCIES, "默认货币", req.currency());
      pref.setCurrency(req.currency());
    }
    if (req.dateFormat() != null) {
      require(DATE_FORMATS, "日期格式", req.dateFormat());
      pref.setDateFormat(req.dateFormat());
    }
    if (req.weekStart() != null) {
      require(WEEK_STARTS, "一周起始日", req.weekStart());
      pref.setWeekStart(req.weekStart());
    }
    if (req.notifyTrip() != null) {
      pref.setNotifyTrip(req.notifyTrip());
    }
    if (req.notifyDocument() != null) {
      pref.setNotifyDocument(req.notifyDocument());
    }
    if (req.notifyMember() != null) {
      pref.setNotifyMember(req.notifyMember());
    }
    if (req.notifySummary() != null) {
      pref.setNotifySummary(req.notifySummary());
    }

    if (pref.getId() == null) {
      mapper.insert(pref);
      pref = findByUser(currentUser.id());
    } else {
      mapper.updateById(pref);
    }
    return Map.of("preferences", toResponse(pref));
  }

  private UserPreference findByUser(String userId) {
    return mapper.selectOne(new LambdaQueryWrapper<UserPreference>()
        .eq(UserPreference::getUserId, userId));
  }

  private void require(Set<String> allowed, String label, String value) {
    if (!allowed.contains(value)) {
      throw ApiException.badRequest(label + "取值不合法：" + value);
    }
  }

  private Map<String, Object> toResponse(UserPreference p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("theme", p.getTheme());
    m.put("timezone", p.getTimezone());
    m.put("distanceUnit", p.getDistanceUnit());
    m.put("currency", p.getCurrency());
    m.put("dateFormat", p.getDateFormat());
    m.put("weekStart", p.getWeekStart());
    m.put("notifyTrip", p.getNotifyTrip());
    m.put("notifyDocument", p.getNotifyDocument());
    m.put("notifyMember", p.getNotifyMember());
    m.put("notifySummary", p.getNotifySummary());
    m.put("updatedAt", p.getUpdatedAt() == null ? null : p.getUpdatedAt().getTime());
    return m;
  }

  /** 更新请求体：所有字段可选，null = 不修改。 */
  public record UpdateRequest(
      String theme,
      String timezone,
      String distanceUnit,
      String currency,
      String dateFormat,
      String weekStart,
      Boolean notifyTrip,
      Boolean notifyDocument,
      Boolean notifyMember,
      Boolean notifySummary) {
  }
}
