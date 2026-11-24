package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.Fmt;
import com.travelos.entity.PackingItem;
import com.travelos.entity.PackingList;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.PackingItemMapper;
import com.travelos.mapper.PackingListMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TripActionService {
  private final TripJourneyService journey;
  private final TripService trips;
  private final TripMemberService members;
  private final UserPreferenceService preferences;
  private final TripSettlementService settlements;
  private final PackingListMapper packingLists;
  private final PackingItemMapper packingItems;
  private final TripPlaceMapper places;

  public TripActionService(TripJourneyService journey, TripService trips, TripMemberService members,
      UserPreferenceService preferences, TripSettlementService settlements, PackingListMapper packingLists,
      PackingItemMapper packingItems, TripPlaceMapper places) {
    this.journey = journey;
    this.trips = trips;
    this.members = members;
    this.preferences = preferences;
    this.settlements = settlements;
    this.packingLists = packingLists;
    this.packingItems = packingItems;
    this.places = places;
  }

  public Map<String, Object> home(String userId, Collection<Trip> accessible, String explicitId,
      LocalDate today, ZoneId zone) {
    return response(userId, journey.choose(accessible, explicitId, today), today, zone);
  }

  public Map<String, Object> detail(String tripId, String userId) {
    members.requireTripMember(tripId, userId);
    Trip trip = trips.findLive(tripId);
    if (trip == null) throw ApiException.notFound("该行程已删除或你已无权访问");
    ZoneId zone = preferences.timeZone(userId);
    LocalDate today = LocalDate.now(zone);
    return response(userId, journey.choose(List.of(trip), tripId, today), today, zone);
  }

  private Map<String, Object> response(String userId, TripJourneyService.Selection selection, LocalDate today, ZoneId zone) {
    var result = new LinkedHashMap<String, Object>();
    result.put("phase", selection.phase());
    result.put("dateKey", today.toString());
    result.put("timeZone", zone.getId());
    Trip trip = selection.trip();
    if (trip == null) {
      result.put("candidates", selection.candidates().stream().map(this::tripInfo).toList());
      if ("none".equals(selection.phase())) result.put("primary", link("创建行程", "/trip-form/"));
      return result;
    }
    String id = trip.getId();
    var member = members.requireTripMember(id, userId);
    boolean canEdit = TripPermission.EDITABLE_ROLES.contains(member.getRole());
    String detail = "/trip-detail/?id=" + id;
    result.put("trip", tripInfo(trip));
    if (trip.getStartDate() != null) {
      result.put("daysUntilStart", ChronoUnit.DAYS.between(today, LocalDate.parse(Fmt.dateKey(trip.getStartDate()))));
    }
    var related = new ArrayList<Map<String, String>>();
    switch (selection.phase()) {
      case "draft" -> result.put("primary", canEdit ? link("继续编辑草稿", "/trip-form/?id=" + id)
          : link("查看草稿", detail));
      case "pre-trip" -> {
        result.put("primary", link("查看出发准备", detail + "#journey-preparation"));
        long pending = pendingPacking(id);
        result.put("message", pending > 0 ? "还有 " + pending + " 件物品待打包" : "核对行程、行李和凭证");
        related.add(link("打包清单", "/packing/?trip=" + id));
        related.add(link("预订凭证", "/vouchers/?trip=" + id));
        related.add(link("证件文档", "/documents/?trip=" + id));
      }
      case "today" -> {
        TripDay day = journey.dayFor(id, "today", today);
        String dayParam = day == null ? "" : "&day=" + day.getId();
        result.put("primary", link(day == null && canEdit ? "查看或补齐行程天" : "打开当天安排",
            "/itinerary/?trip=" + id + dayParam + "&date=" + today));
        if (day == null) {
          result.put("message", "今天还没有建立行程天");
        } else if (places.selectCount(new LambdaQueryWrapper<TripPlace>().eq(TripPlace::getTripDayId, day.getId())) == 0) {
          result.put("message", "今天还没有安排");
        }
        related.add(link("当天凭证", "/vouchers/?trip=" + id + "&date=" + today));
        related.add(link("当天地图", "/map/?trip=" + id + dayParam));
      }
      case "past" -> {
        try {
          Object byCurrency = settlements.preview(id, userId).get("byCurrency");
          boolean pending = byCurrency instanceof Map<?, ?> groups && groups.values().stream()
              .anyMatch(value -> value instanceof Map<?, ?> group && (Boolean.TRUE.equals(group.get("canConfirm"))
                  || group.get("confirmBlockedReason") != null));
          result.put("primary", pending ? link("查看待对账款项", "/expenses/?trip=" + id + "&tab=settlement")
              : link("查看行程回顾", canEdit ? detail + "#recapModule" : "/journals/?trip=" + id));
        } catch (RuntimeException ex) {
          result.put("primary", link("查看旅行账目", "/expenses/?trip=" + id + "&tab=settlement"));
          result.put("message", "暂时无法核对结算状态");
        }
      }
      case "archived" -> result.put("primary", link("查看已归档行程", detail));
      default -> throw new IllegalStateException("Unknown journey phase");
    }
    result.put("related", related);
    return result;
  }

  private long pendingPacking(String tripId) {
    List<String> ids = packingLists.selectList(new LambdaQueryWrapper<PackingList>()
        .eq(PackingList::getTripId, tripId)).stream().map(PackingList::getId).toList();
    return ids.isEmpty() ? 0 : packingItems.selectCount(new LambdaQueryWrapper<PackingItem>()
        .in(PackingItem::getPackingListId, ids).eq(PackingItem::getIsPacked, false));
  }

  private Map<String, Object> tripInfo(Trip trip) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", trip.getId());
    result.put("title", trip.getTitle());
    result.put("destination", trip.getDestination());
    result.put("startDate", trip.getStartDate() == null ? null : Fmt.dateKey(trip.getStartDate()));
    return result;
  }

  private Map<String, String> link(String label, String url) {
    return Map.of("label", label, "url", url);
  }
}
