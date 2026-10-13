package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TripMemberService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/trips/{tripId}/members")
public class TripMemberController {

  private final TripMemberService tripMemberService;

  public TripMemberController(TripMemberService tripMemberService) {
    this.tripMemberService = tripMemberService;
  }

  @GetMapping
  public Map<String, Object> findMembers(@PathVariable String tripId,
      @CurrentUser UserContext user) {
    return tripMemberService.findMembers(tripId, user.id());
  }

  @PostMapping
  public Map<String, Object> invite(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    Object username = dto.get("username");
    Object role = dto.get("role");
    if (username == null || username.toString().isBlank()) {
      throw new IllegalArgumentException("username should not be empty");
    }
    return tripMemberService.invite(tripId, user.id(),
        username.toString(), role == null ? null : role.toString());
  }

  @PatchMapping("/{memberId}/role")
  public Map<String, Object> updateRole(@PathVariable String tripId,
      @PathVariable String memberId, @RequestBody Map<String, Object> dto,
      @CurrentUser UserContext user) {
    Object role = dto.get("role");
    if (role == null || role.toString().isBlank()) {
      throw new IllegalArgumentException("role should not be empty");
    }
    return tripMemberService.updateRole(tripId, memberId, role.toString(), user.id());
  }

  @DeleteMapping("/{memberId}")
  public Map<String, Object> remove(@PathVariable String tripId, @PathVariable String memberId,
      @CurrentUser UserContext user) {
    return tripMemberService.remove(tripId, memberId, user.id());
  }
}
