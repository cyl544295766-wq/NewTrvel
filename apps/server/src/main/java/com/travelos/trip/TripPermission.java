package com.travelos.trip;

import java.util.List;

/** Role permissions, mirroring trip-permission.type.ts. */
public final class TripPermission {

  public static final String OWNER = "owner";
  public static final String ADMIN = "admin";
  public static final String MEMBER = "member";
  public static final String VIEWER = "viewer";

  public static final List<String> EDITABLE_ROLES = List.of(OWNER, ADMIN, MEMBER);

  /** 成员管理操作（邀请/改角色/移除）允许的操作者角色。 */
  public static final List<String> MANAGER_ROLES = List.of(OWNER, ADMIN);

  /** 邀请/改角色时允许赋给目标成员的角色；owner 只能通过专门的转移流程产生。 */
  public static final List<String> ASSIGNABLE_ROLES = List.of(ADMIN, MEMBER, VIEWER);

  private TripPermission() {
  }
}
