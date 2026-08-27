package io.akka.uptimekuma.checks;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

/** Stand-ins for the things a check reads that cannot be started inside a test. */
final class Fakes {

  private Fakes() {}

  /**
   * A result set of a given width holding the values supplied, one per row.
   *
   * <p>Built by hand rather than by starting a database, because the rule under test is about the
   * shape of an answer — how many rows, how many columns — and every engine hands that shape over
   * through the same two methods.
   */
  static ResultSet rows(int columnCount, Object... values) {
    List<Object> rows = java.util.Arrays.asList(values);
    int[] cursor = {-1};

    ResultSetMetaData meta =
        (ResultSetMetaData)
            Proxy.newProxyInstance(
                Fakes.class.getClassLoader(),
                new Class<?>[] {ResultSetMetaData.class},
                (proxy, method, args) ->
                    "getColumnCount".equals(method.getName()) ? columnCount : null);

    return (ResultSet)
        Proxy.newProxyInstance(
            Fakes.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "next" -> ++cursor[0] < rows.size();
                  case "getMetaData" -> meta;
                  case "getObject" -> rows.get(cursor[0]);
                  case "close" -> null;
                  default -> null;
                });
  }
}
