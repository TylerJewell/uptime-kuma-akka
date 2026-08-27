package io.akka.uptimekuma.notifications;

import java.util.LinkedHashMap;
import java.util.Map;

/** Where a composed request goes. */
public interface Sender {

  Response send(Request request) throws Exception;

  /**
   * @param body null for a request with no body, which is what a GET provider composes
   * @param formFields set instead of {@code body} where the source builds multipart form data, so
   *     a comparison can look at the fields rather than at a boundary string that differs every
   *     time
   */
  record Request(
      String method,
      String url,
      Map<String, String> headers,
      String body,
      Map<String, String> formFields) {

    public static Request get(String url, Map<String, String> headers) {
      return new Request("GET", url, headers, null, null);
    }

    public static Request post(String url, Map<String, String> headers, String body) {
      return new Request("POST", url, headers, body, null);
    }

    public static Request form(String url, Map<String, String> headers, Map<String, String> fields) {
      return new Request("POST", url, headers, null, fields);
    }
  }

  /**
   * @param statusText the reason phrase. Four providers put it in the message they return, so it is
   *     carried rather than discarded.
   */
  record Response(int status, String statusText, String body) {

    public Map<String, Object> json() {
      try {
        return Json.MAPPER.readValue(body, Map.class);
      } catch (Exception e) {
        return new LinkedHashMap<>();
      }
    }

    public boolean ok() {
      return status >= 200 && status < 300;
    }
  }
}
