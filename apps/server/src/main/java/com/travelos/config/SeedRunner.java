package com.travelos.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.entity.SystemSetting;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.User;
import com.travelos.mapper.SystemSettingMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.UserMapper;
import com.travelos.service.TripDayService;
import com.travelos.service.TripPlaceService;
import com.travelos.service.TripService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

  private final SystemSettingMapper systemSettingMapper;
  private final UserMapper userMapper;
  private final TripService tripService;
  private final TripDayService tripDayService;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceService tripPlaceService;
  private final TripMapper tripMapper;
  private final String adminUsername;
  private final String adminDisplayName;
  private final String adminPassword;

  public SeedRunner(SystemSettingMapper systemSettingMapper, UserMapper userMapper,
      TripService tripService, TripDayService tripDayService, TripDayMapper tripDayMapper,
      TripPlaceService tripPlaceService, TripMapper tripMapper,
      @Value("${seed.admin-username}") String adminUsername,
      @Value("${seed.admin-display-name}") String adminDisplayName,
      @Value("${seed.admin-password}") String adminPassword) {
    this.systemSettingMapper = systemSettingMapper;
    this.userMapper = userMapper;
    this.tripService = tripService;
    this.tripDayService = tripDayService;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceService = tripPlaceService;
    this.tripMapper = tripMapper;
    this.adminUsername = adminUsername;
    this.adminDisplayName = adminDisplayName;
    this.adminPassword = adminPassword;
  }

  @Override
  public void run(ApplicationArguments args) {
    // 种子初始化无请求上下文，显式绕过行程数据范围过滤
    com.travelos.common.TripScopeContext.runBypassed(() -> {
      try {
        seed();
      } catch (Exception ex) {
        log.warn("Seed skipped: {}", ex.getMessage());
      }
    });
  }

  private void seed() {
      SystemSetting existing = systemSettingMapper
          .selectOne(new LambdaQueryWrapper<SystemSetting>().eq(SystemSetting::getKey, "app.name"));
      if (existing == null) {
        SystemSetting setting = new SystemSetting();
        setting.setKey("app.name");
        setting.setValue("Travel OS");
        systemSettingMapper.insert(setting);
      } else {
        existing.setValue("Travel OS");
        systemSettingMapper.updateById(existing);
      }

      User admin = userMapper
          .selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, adminUsername));
      User toSave = admin == null ? new User() : admin;
      toSave.setUsername(adminUsername);
      toSave.setDisplayName(adminDisplayName);
      BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
      // P2-4：仅首次创建时写入种子密码；已存在的 admin 不再每次启动被重置回配置值
      if (admin == null) {
        toSave.setPasswordHash(encoder.encode(adminPassword));
      } else if (encoder.matches(adminPassword, admin.getPasswordHash())) {
        log.warn("管理员账户 {} 仍在使用种子/默认密码，请尽快在「账户设置」中修改", adminUsername);
      }
      if (toSave.getStatus() == null) {
        toSave.setStatus("active");
      }
      toSave.setRole("super_admin"); // 种子管理员恒为超级管理员
      if (admin == null) {
        userMapper.insert(toSave);
      } else {
        userMapper.updateById(toSave);
      }
      log.info("Seed completed (admin user: {})", adminUsername);

      try {
        seedSampleTrips(toSave.getId());
      } catch (Exception ex) {
        log.warn("示例行程种子失败（不影响启动）: {}", ex.getMessage());
      }
  }

  // ==================== 示例行程种子（新用户首次可感知的产品全貌 + 广场种子内容） ====================

  /** 一条示例地点：名称/类型/地址/坐标/建议时刻/备注。 */
  private record SamplePlace(String name, String type, String address,
      double lat, double lng, String startTime, String note) {
  }

  /** 示例行程幂等：已存在任意「示例·」标题的行程则跳过。 */
  private void seedSampleTrips(String adminId) {
    Long existing = tripMapper.selectCount(new LambdaQueryWrapper<Trip>()
        .likeRight(Trip::getTitle, "示例·"));
    if (existing != null && existing > 0) {
      return;
    }
    seedSampleTrip(adminId, "示例·北京经典 4 日", "北京",
        "皇城根下的经典四日：中轴线古都风华、长城壮阔、皇家园林与胡同烟火气。",
        List.of(
            List.of(
                new SamplePlace("天安门广场", "attraction", "北京市东城区长安街", 39.9087, 116.3975,
                    "09:00", "广场看升旗建议提前早起，需安检"),
                new SamplePlace("故宫博物院", "attraction", "北京市东城区景山前街4号", 39.9163, 116.3972,
                    "10:30", "周一闭馆，门票需提前在官方渠道预约"),
                new SamplePlace("景山公园", "attraction", "北京市西城区景山西街44号", 39.9254, 116.3968,
                    "15:30", "万春亭俯瞰紫禁城全景，日落尤其出片")),
            List.of(
                new SamplePlace("八达岭长城", "attraction", "北京市延庆区八达岭镇", 40.3598, 116.0199,
                    "09:00", "建议乘市郊铁路 S2 线或包车，好汉坡较陡"),
                new SamplePlace("奥林匹克公园", "attraction", "北京市朝阳区科荟路", 39.9928, 116.3966,
                    "16:30", "鸟巢/水立方夜景，晚上灯光更好看")),
            List.of(
                new SamplePlace("颐和园", "attraction", "北京市海淀区新建宫门路19号", 39.9998, 116.2755,
                    "09:30", "皇家园林，昆明湖长廊慢慢走半天不亏"),
                new SamplePlace("圆明园遗址公园", "attraction", "北京市海淀区清华西路28号", 40.0083, 116.2983,
                    "14:30", "大水法遗址与荷花池，夏季尤佳")),
            List.of(
                new SamplePlace("天坛公园", "attraction", "北京市东城区天坛东里甲1号", 39.8822, 116.4066,
                    "09:30", "祈年殿早上去人少，古柏很出片"),
                new SamplePlace("前门大街 · 大栅栏", "shopping", "北京市东城区前门大街", 39.8994, 116.3986,
                    "14:00", "老字号小吃聚集，适合边逛边吃"),
                new SamplePlace("南锣鼓巷", "attraction", "北京市东城区南锣鼓巷胡同", 39.9378, 116.4032,
                    "17:00", "胡同与文创小店，傍晚氛围最好"))));
    seedSampleTrip(adminId, "示例·大理丽江 5 日", "大理、丽江",
        "从苍山洱海到雪山古城：环湖骑行、古镇慢逛与玉龙雪山，一场慢节奏的滇西北之旅。",
        List.of(
            List.of(
                new SamplePlace("大理古城", "attraction", "云南省大理白族自治州大理市", 25.6930, 100.1580,
                    "15:00", "人民路的小店与咖啡馆值得慢慢逛"),
                new SamplePlace("洱海生态廊道", "attraction", "大理市洱海西岸", 25.7240, 100.1900,
                    "17:00", "日落时分骑行最美，可租电动车")),
            List.of(
                new SamplePlace("崇圣寺三塔", "attraction", "大理市崇圣寺三塔文化旅游区", 25.7140, 100.1470,
                    "09:30", "大理地标，倒影公园拍照最经典"),
                new SamplePlace("喜洲古镇", "attraction", "大理市喜洲镇", 25.8560, 100.1220,
                    "14:00", "白族民居建筑群，尝一口现烤喜洲粑粑")),
            List.of(
                new SamplePlace("双廊古镇", "attraction", "大理市双廊镇", 25.9450, 100.1890,
                    "10:00", "洱海东岸观海绝佳位置，海地生活白桌子打卡"),
                new SamplePlace("小普陀", "attraction", "大理市挖色镇洱海中", 25.9680, 100.2100,
                    "15:00", "湖心小岛，喂海鸥的季节在冬天")),
            List.of(
                new SamplePlace("丽江古城", "attraction", "云南省丽江市古城区", 26.8720, 100.2300,
                    "15:00", "四方街迷宫巷子，傍晚酒吧街渐热闹"),
                new SamplePlace("狮子山万古楼", "attraction", "丽江古城狮子山", 26.8750, 100.2290,
                    "17:30", "俯瞰古城全景与远处玉龙雪山")),
            List.of(
                new SamplePlace("玉龙雪山冰川公园", "attraction", "丽江市玉龙纳西族自治县", 27.1164, 100.1700,
                    "09:00", "大索道票需提前预约，备氧气瓶与羽绒服"),
                new SamplePlace("蓝月谷", "attraction", "玉龙雪山景区内", 27.1010, 100.1830,
                    "13:30", "雪山融水湖泊，晴天湖水奶蓝色"))));
  }

  private void seedSampleTrip(String adminId, String title, String destination, String description,
      List<List<SamplePlace>> daysPlan) {
    LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(7);
    LocalDate end = start.plusDays(daysPlan.size() - 1L);
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("title", title);
    dto.put("description", description);
    dto.put("destination", destination);
    dto.put("startDate", start.toString());
    dto.put("endDate", end.toString());
    dto.put("visibility", "public");
    Map<String, Object> res = tripService.create(dto, adminId);
    @SuppressWarnings("unchecked")
    Map<String, Object> tripMap = (Map<String, Object>) res.get("trip");
    String tripId = String.valueOf(tripMap.get("id"));

    tripDayService.generate(tripId, adminId);
    List<TripDay> days = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId)
        .orderByAsc(TripDay::getDayIndex));
    Map<Integer, String> dayIdByIndex = new LinkedHashMap<>();
    for (TripDay day : days) {
      dayIdByIndex.put(day.getDayIndex(), day.getId());
    }

    int created = 0;
    for (int i = 0; i < daysPlan.size(); i++) {
      String dayId = dayIdByIndex.get(i + 1);
      if (dayId == null) {
        continue;
      }
      LocalDate dayDate = start.plusDays(i);
      for (SamplePlace place : daysPlan.get(i)) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripDayId", dayId);
        payload.put("name", place.name());
        payload.put("type", place.type());
        payload.put("address", place.address());
        payload.put("latitude", String.valueOf(place.lat()));
        payload.put("longitude", String.valueOf(place.lng()));
        payload.put("startTime", dayDate + " " + place.startTime() + ":00");
        payload.put("notes", place.note());
        try {
          tripPlaceService.create(tripId, payload, adminId);
          created++;
        } catch (Exception ex) {
          log.warn("示例地点写入失败 {} - {}: {}", title, place.name(), ex.getMessage());
        }
      }
    }
    log.info("Sample trip seeded: {} ({} days, {} places)", title, daysPlan.size(), created);
  }
}
