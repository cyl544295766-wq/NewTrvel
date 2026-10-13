package com.travelos.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/** Formatting helpers that mirror the JSON output of the previous NestJS API. */
public final class Fmt {

  private Fmt() {
  }

  /** Money-like values serialize as plain strings with exactly 2 decimals. */
  public static String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** Coordinates keep meaningful precision, like Prisma's Decimal.toString(). */
  public static String coord(BigDecimal value) {
    if (value == null) {
      return null;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    if (stripped.scale() < 0) {
      stripped = stripped.setScale(0);
    }
    return stripped.toPlainString();
  }

  /** yyyy-MM-dd in UTC, the way the previous API keyed dates. */
  public static String dateKey(Date date) {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format.format(date);
  }
}
