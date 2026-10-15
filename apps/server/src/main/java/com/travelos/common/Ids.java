package com.travelos.common;

import java.security.SecureRandom;

/**
 * Generates cuid-like identifiers matching the format created by Prisma:
 * "c" + 24 lowercase base36 characters (8-char timestamp + 16 random chars).
 */
public final class Ids {

  private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  private Ids() {
  }

  public static String next() {
    StringBuilder builder = new StringBuilder(25);
    builder.append('c');
    String timestamp = Long.toString(System.currentTimeMillis(), 36);
    for (int i = 0; i < 8 - timestamp.length(); i++) {
      builder.append('0');
    }
    builder.append(timestamp);
    for (int i = 0; i < 16; i++) {
      builder.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
    }
    return builder.toString();
  }
}
