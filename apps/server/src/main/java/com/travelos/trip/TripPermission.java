package com.travelos.trip;

import java.util.List;

/** Role permissions, mirroring trip-permission.type.ts. */
public final class TripPermission {

  public static final String OWNER = "owner";
  public static final String ADMIN = "admin";
  public static final String MEMBER = "member";
  public static final String VIEWER = "viewer";

  public static final List<String> EDITABLE_ROLES = List.of(OWNER, ADMIN, MEMBER);

  private TripPermission() {
  }
}
