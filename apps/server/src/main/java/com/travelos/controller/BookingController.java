package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.BookingService;
import com.travelos.trip.RequireTripRole;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 预订管理（设计文档 3.3.1 / 3.3.2）。 */
@RestController
@RequestMapping("/api/trips/{tripId}/bookings")
public class BookingController {

  private final BookingService bookingService;

  public BookingController(BookingService bookingService) {
    this.bookingService = bookingService;
  }

  @GetMapping
  @RequireTripRole({"owner", "admin", "member", "viewer"})
  public Map<String, Object> findAll(@PathVariable String tripId, @CurrentUser UserContext user) {
    return bookingService.findAll(tripId, user.id());
  }

  /** 凭证速查聚合（3.3.2）：确认码/订单号/附件摘要，按时间正序。 */
  @GetMapping("/vouchers")
  @RequireTripRole({"owner", "admin", "member", "viewer"})
  public Map<String, Object> vouchers(@PathVariable String tripId, @CurrentUser UserContext user) {
    return bookingService.vouchers(tripId, user.id());
  }

  /** 提醒列表：各预订的提醒时间与开关状态。 */
  @GetMapping("/reminders")
  @RequireTripRole({"owner", "admin", "member", "viewer"})
  public Map<String, Object> reminders(@PathVariable String tripId, @CurrentUser UserContext user) {
    return bookingService.reminders(tripId, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping
  public Map<String, Object> create(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return bookingService.create(tripId, user.id(), dto);
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/{bookingId}")
  public Map<String, Object> update(@PathVariable String tripId, @PathVariable String bookingId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return bookingService.update(tripId, bookingId, dto, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @DeleteMapping("/{bookingId}")
  public Map<String, Object> delete(@PathVariable String tripId, @PathVariable String bookingId,
      @CurrentUser UserContext user) {
    return bookingService.delete(tripId, bookingId, user.id());
  }

  /** P1-1 一键核销：预订金额转为费用（等分给全部成员），booking 记 expense_id 并退出预估占用。 */
  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping("/{bookingId}/convert-expense")
  public Map<String, Object> convertExpense(@PathVariable String tripId,
      @PathVariable String bookingId, @CurrentUser UserContext user) {
    return bookingService.convertToExpense(tripId, bookingId, user.id());
  }
}
