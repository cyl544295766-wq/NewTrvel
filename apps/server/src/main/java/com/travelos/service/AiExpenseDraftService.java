package com.travelos.service;

import com.travelos.common.ApiException;
import com.travelos.common.Payloads;
import com.travelos.entity.Trip;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将用户在对话中指定的人和分摊方式转为账本使用的参数。 */
@Service
public class AiExpenseDraftService {
  private final TripService trips;
  private final TripMemberService members;
  private final TripExpenseService expenses;

  public AiExpenseDraftService(TripService trips, TripMemberService members, TripExpenseService expenses) {
    this.trips = trips;
    this.members = members;
    this.expenses = expenses;
  }

  public List<Map<String, Object>> members(String tripId, String userId) {
    members.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    return expenses.getMembers(tripId);
  }

  public Map<String, Object> preview(String tripId, String userId, Map<String, Object> args, String category) {
    List<Map<String, Object>> candidates = members(tripId, userId);
    Trip trip = trips.findLive(tripId);
    if (trip == null) {
      throw ApiException.notFound("旅行不存在");
    }
    String currency = currency(args.get("currency"));
    String payer = Payloads.trimmedOrNull(args.get("payer"));
    String payerId = payer == null ? userId : resolveMember(payer, userId, candidates, "付款人");
    String split = Payloads.trimmedOrNull(args.get("splitType"));
    String splitType = split == null ? "equal" : split;
    if (!List.of("equal", "ratio", "amount").contains(splitType)) {
      throw ApiException.badRequest("请选择均分、按比例或按金额分摊");
    }
    Map<String, Object> expense = new LinkedHashMap<>();
    expense.put("amount", args.get("amount"));
    expense.put("category", category);
    expense.put("currency", currency);
    expense.put("payerUserId", payerId);
    expense.put("splitType", splitType);
    expense.put("title", args.get("title"));
    expense.put("notes", args.get("notes"));

    boolean allMembers = args.get("participants") == null;
    if (allMembers) {
      if (args.get("participantCount") != null || !"equal".equals(splitType)) {
        throw ApiException.badRequest("请确认具体参与分摊的成员");
      }
      expense.put("shareUserIds", candidates.stream().map(row -> row.get("userId").toString()).toList());
    } else {
      addParticipants(expense, args, userId, candidates, splitType);
    }

    Map<String, Object> preview = new LinkedHashMap<>(expenses.previewAiExpense(tripId, userId, expense));
    preview.put("allMembers", allMembers);
    preview.put("tripTitle", trip.getTitle());
    Set<String> displayed = new LinkedHashSet<>();
    displayed.add(payerId);
    for (Object share : (List<?>) preview.get("shares")) {
      displayed.add(((Map<?, ?>) share).get("userId").toString());
    }
    Map<String, String> labels = new LinkedHashMap<>();
    for (Map<String, Object> candidate : candidates) {
      String id = candidate.get("userId").toString();
      if (!displayed.contains(id)) {
        continue;
      }
      String username = Payloads.trimmedOrNull(candidate.get("username"));
      String displayName = Payloads.trimmedOrNull(candidate.get("displayName"));
      String label = displayName == null ? username
          : displayName.equals(username) || username == null ? displayName : displayName + "（" + username + "）";
      labels.put(id, label);
    }
    preview.put("memberLabels", labels);
    return Map.of("expense", expense, "expensePreview", preview);
  }

  private void addParticipants(Map<String, Object> expense, Map<String, Object> args, String userId,
      List<Map<String, Object>> candidates, String splitType) {
    if (!(args.get("participants") instanceof List<?> entries) || entries.isEmpty()) {
      throw ApiException.badRequest("请确认具体参与分摊的成员");
    }
    String count = Payloads.trimmedOrNull(args.get("participantCount"));
    if (count != null && !count.equals(Integer.toString(entries.size()))) {
      throw ApiException.badRequest("分摊人数与已确认的成员数不同，请确认参与成员");
    }
    Set<String> ids = new LinkedHashSet<>();
    List<Map<String, String>> shares = new ArrayList<>();
    for (Object item : entries) {
      if (!(item instanceof Map<?, ?> entry)) {
        throw ApiException.badRequest("请确认分摊成员及其份额");
      }
      String id = resolveMember(Payloads.trimmedOrNull(entry.get("member")), userId, candidates, "分摊成员");
      if (!ids.add(id)) {
        throw ApiException.badRequest("分摊成员不能重复");
      }
      if ("equal".equals(splitType)) {
        if (entry.get("ratio") != null || entry.get("shareAmount") != null) {
          throw ApiException.badRequest("分摊明细包含比例或金额，请确认分摊方式");
        }
      } else {
        String field = "ratio".equals(splitType) ? "ratio" : "shareAmount";
        String value = Payloads.trimmedOrNull(entry.get(field));
        if (value == null || entry.get("ratio") != null && entry.get("shareAmount") != null) {
          throw ApiException.badRequest("请确认每位成员的分摊比例或金额");
        }
        shares.add(Map.of("userId", id, field, value));
      }
    }
    if ("equal".equals(splitType)) {
      expense.put("shareUserIds", List.copyOf(ids));
    } else {
      expense.put("shares", shares);
    }
  }

  private String resolveMember(String reference, String userId,
      List<Map<String, Object>> candidates, String role) {
    if (reference == null) {
      throw ApiException.badRequest("请确认" + role);
    }
    String selector = List.of("我", "本人", "当前用户", "我自己").contains(reference) ? userId : reference;
    for (Map<String, Object> row : candidates) {
      if (selector.equals(row.get("userId"))) {
        return selector;
      }
    }
    List<Map<String, Object>> matches = candidates.stream()
        .filter(row -> selector.equalsIgnoreCase(Payloads.text(row.get("displayName")))
            || selector.equalsIgnoreCase(Payloads.text(row.get("username"))))
        .toList();
    if (matches.size() != 1) {
      throw ApiException.badRequest(matches.isEmpty() ? role + "必须是当前行程的成员，请确认"
          : role + "有重名，请确认具体用户名");
    }
    return matches.get(0).get("userId").toString();
  }

  private String currency(Object raw) {
    String value = TripExpenseService.normalizeCurrency(Payloads.text(raw));
    try {
      Currency code = Currency.getInstance(value);
      if (code.getDefaultFractionDigits() >= 0) {
        return code.getCurrencyCode();
      }
    } catch (IllegalArgumentException ignored) {
      // 未知币种和无法区分的币种符号均由用户确认。
    }
    throw ApiException.badRequest("请确认原币币种，例如 CNY、USD 或 JPY");
  }
}
