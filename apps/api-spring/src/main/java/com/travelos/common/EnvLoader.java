package com.travelos.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads .env into System properties so Spring property placeholders can pick them up.
 * Search order: $user.dir/.env, $user.dir/../.env, $user.dir/../../.env
 */
public final class EnvLoader {

  private EnvLoader() {
  }

  public static void loadDotEnv() {
    for (Path candidate : candidates()) {
      if (Files.isRegularFile(candidate)) {
        apply(parse(candidate));
        return;
      }
    }
  }

  private static java.util.List<Path> candidates() {
    Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    return java.util.List.of(cwd.resolve(".env"), cwd.getParent() == null ? cwd.resolve(".env")
        : cwd.getParent().resolve(".env"), cwd.getParent() == null || cwd.getParent().getParent() == null
            ? cwd.resolve(".env")
            : cwd.getParent().getParent().resolve(".env"));
  }

  static Map<String, String> parse(Path file) {
    Map<String, String> values = new HashMap<>();
    try {
      for (String rawLine : Files.readAllLines(file)) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\""))) {
          value = value.substring(1, value.length() - 1);
        }
        values.put(key, value);
      }
    } catch (Exception ignored) {
      // fall through with what we have
    }
    return values;
  }

  private static void apply(Map<String, String> values) {
    for (Map.Entry<String, String> entry : values.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      // Environment variables take precedence over .env
      if (System.getenv().get(key) != null && !System.getenv().get(key).isBlank()) {
        continue;
      }
      if (key.equals("DATABASE_URL")) {
        applyDatabaseUrl(value);
        continue;
      }
      if (System.getProperty(key) == null) {
        System.setProperty(key, value);
      }
      if (key.equals("APP_PORT") && System.getProperty("server.port") == null) {
        System.setProperty("server.port", value);
      }
    }
  }

  private static void applyDatabaseUrl(String url) {
    // postgresql://user:password@host:port/database
    try {
      String rest = url.substring("postgresql://".length());
      int slash = rest.indexOf('/');
      String hostPart = rest.substring(0, slash);
      String database = rest.substring(slash + 1);
      String user;
      String password;
      int at = hostPart.indexOf('@');
      if (at >= 0) {
        String credentials = hostPart.substring(0, at);
        hostPart = hostPart.substring(at + 1);
        int colon = credentials.indexOf(':');
        user = credentials.substring(0, colon);
        password = credentials.substring(colon + 1);
      } else {
        user = "postgres";
        password = "";
      }
      String jdbc = "jdbc:postgresql://" + hostPart + "/" + database + "?stringtype=unspecified";
      System.setProperty("spring.datasource.url", jdbc);
      System.setProperty("spring.datasource.username", user);
      System.setProperty("spring.datasource.password", password);
    } catch (Exception ignored) {
      // invalid DATABASE_URL; let Boot fail with a clear message
    }
  }
}
