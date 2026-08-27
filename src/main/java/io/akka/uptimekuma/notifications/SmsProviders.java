package io.akka.uptimekuma.notifications;

import static io.akka.uptimekuma.notifications.Notify.OK;
import static io.akka.uptimekuma.notifications.Notify.asciiOnly;
import static io.akka.uptimekuma.notifications.Notify.basic;
import static io.akka.uptimekuma.notifications.Notify.fields;
import static io.akka.uptimekuma.notifications.Notify.form;
import static io.akka.uptimekuma.notifications.Notify.headers;
import static io.akka.uptimekuma.notifications.Notify.splitList;
import static io.akka.uptimekuma.notifications.Notify.urlEncode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The gateways that deliver a message as a text message or a phone call.
 *
 * <p>Grouped together because they share a shape the others do not: the message is flattened to
 * seven-bit characters, truncated to a length the network imposes, and the answer is a body that
 * reports failure with a two-hundred status — so each one inspects what came back rather than
 * trusting the status code.
 */
final class SmsProviders {

  private SmsProviders() {}

  static List<Provider> all() {
    return List.of(
        new Elks(),
        new BearSms(),
        new Cellsynt(),
        new ClickSendSms(),
        new EgoSms(),
        new FreeMobile(),
        new GtxMessaging(),
        new Octopush(),
        new Ooredoo(),
        new Plivo(),
        new PromoSms(),
        new SerwerSms(),
        new SevenIO(),
        new Signal(),
        new SmsGateway(),
        new SmsPlanet(),
        new Smsc(),
        new SmsEagle(),
        new SmsIr(),
        new SmsManager(),
        new SmsPartner(),
        new Telnyx(),
        new Teltonika(),
        new Threema(),
        new Twilio(),
        new AliyunSms(),
        new CallMeBot());
  }

  static final class Elks implements Provider {
    @Override
    public String name() {
      return "Elks";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.46elks.com/a1/sms",
                  headers(
                      "Authorization", basic(c.str("elksUsername"), c.str("elksAuthToken")),
                      "Content-Type", "application/x-www-form-urlencoded"),
                  form(
                      fields(
                          "from", c.str("elksFromNumber"),
                          "to", c.str("elksToNumber"),
                          "message", msg))));
      return OK;
    }
  }

  static final class BearSms implements Provider {
    @Override
    public String name() {
      return "bearsms";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      // The two status prefixes are stripped with their trailing space, so a message that opened
      // with one no longer starts with a blank.
      String clean = msg.replace("🔴 ", "").replace("✅ ", "");
      StringBuilder url = new StringBuilder("https://app.bearsms.com/index.php?");
      Map<String, String> params =
          fields(
              "app", "ws",
              "u", c.str("bearsmsUsername"),
              "h", c.str("bearsmsHashKey"),
              "op", "pv",
              "to", c.str("bearsmsPhoneNumber"),
              "msg", clean);
      if (c.truthy("bearsmsSenderId")) {
        params.put("from", c.str("bearsmsSenderId"));
      }
      if (Notify.hasNonAscii(clean)) {
        params.put("unicode", "1");
      }
      url.append(form(params));
      Sender.Response response = ctx.sender().send(Sender.Request.get(url.toString(), headers()));
      Map<String, Object> body = response.json();
      Object data = body.get("data");
      boolean anyOk = false;
      if (data instanceof List<?> rows) {
        for (Object row : rows) {
          if (row instanceof Map<?, ?> entry && "OK".equals(entry.get("status"))) {
            anyOk = true;
          }
        }
      }
      if ("ERR".equals(body.get("status")) || body.get("error_string") != null || !anyOk) {
        Object error = body.get("error_string");
        throw new Exception(error != null ? String.valueOf(error) : Json.write(body));
      }
      return OK;
    }
  }

  static final class Cellsynt implements Provider {
    @Override
    public String name() {
      return "Cellsynt";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Map<String, String> params =
          fields(
              "username", c.str("cellsyntLogin"),
              "password", c.str("cellsyntPassword"),
              "destination", c.str("cellsyntDestination"),
              "text", asciiOnly(msg),
              "originatortype", c.str("cellsyntOriginatortype"),
              "originator", c.str("cellsyntOriginator"),
              // Six concatenated parts is the maximum a long message may span; one means the
              // message is cut at a single part instead.
              "allowconcat", c.truthy("cellsyntAllowLongSMS") ? "6" : "1");
      Sender.Response response =
          ctx.sender()
              .send(
                  new Sender.Request(
                      "POST", "https://se-1.cellsynt.net/sms.php?" + form(params), headers(), null,
                      null));
      if (response.body() == null) {
        throw new Exception("Could not connect to Cellsynt, please try again.");
      }
      if (response.body().contains("Error:")) {
        throw new Exception(response.body().replace("Error:", ""));
      }
      return OK;
    }
  }

  static final class ClickSendSms implements Provider {
    @Override
    public String name() {
      return "clicksendsms";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String body =
          Json.obj()
              .put(
                  "messages",
                  Json.array(
                      Json.obj()
                          .put("body", asciiOnly(msg))
                          .put("to", c.str("clicksendsmsToNumber"))
                          .put("source", "uptime-kuma")
                          .put("from", c.str("clicksendsmsSenderName"))
                          .map()))
              .toString();
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://rest.clicksend.com/v3/sms/send",
                      headers(
                          "Content-Type", "application/json",
                          "Authorization",
                              basic(c.str("clicksendsmsLogin"), c.str("clicksendsmsPassword")),
                          "Accept", "text/json"),
                      body));
      String status = firstMessageStatus(response);
      if (!"SUCCESS".equals(status)) {
        throw new Exception("Something gone wrong. Api returned " + status + ".");
      }
      return OK;
    }

    @SuppressWarnings("unchecked")
    private static String firstMessageStatus(Sender.Response response) {
      Object outer = response.json().get("data");
      if (outer instanceof Map<?, ?> data) {
        Object messages = ((Map<String, Object>) data).get("messages");
        if (messages instanceof List<?> list && !list.isEmpty()) {
          Object first = list.get(0);
          if (first instanceof Map<?, ?> entry) {
            return String.valueOf(entry.get("status"));
          }
        }
      }
      return "null";
    }
  }

  static final class EgoSms implements Provider {
    @Override
    public String name() {
      return "egosms";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Map<String, String> params =
          fields(
              "number", c.str("egosmsPhoneNumber"),
              "message", msg,
              "username", c.str("egosmsUsername"),
              "password", c.str("egosmsPassword"),
              "sender", c.str("egosmsSender", "EGOSMS"),
              "priority", "0");
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.get(
                      "https://www.egosms.co/api/v1/plain/?" + form(params), headers()));
      // The gateway answers with a plain-text receipt, and the source hands that back as the
      // success message rather than a fixed one.
      return response.body() == null || response.body().isEmpty() ? OK : response.body();
    }
  }

  static final class FreeMobile implements Provider {
    @Override
    public String name() {
      return "FreeMobile";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      // Only the first occurrence, because the source substitutes with a string rather than a
      // global pattern.
      String text = msg.replaceFirst("🔴", "⛔️");
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://smsapi.free-mobile.fr/sendmsg?msg=" + urlEncode(text),
                  headers("Content-Type", "application/json"),
                  Json.obj()
                      .put("user", c.str("freemobileUser"))
                      .put("pass", c.str("freemobilePass"))
                      .toString()));
      return OK;
    }
  }

  static final class GtxMessaging implements Provider {
    @Override
    public String name() {
      return "gtxmessaging";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String text = msg.replace("🔴 ", "").replace("✅ ", "");
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://rest.gtx-messaging.net/smsc/sendsms/"
                      + c.str("gtxMessagingApiKey")
                      + "/json",
                  headers("Content-Type", "application/x-www-form-urlencoded"),
                  form(
                      fields(
                          "from", c.str("gtxMessagingFrom") == null
                              ? null
                              : c.str("gtxMessagingFrom").trim(),
                          "to", c.str("gtxMessagingTo") == null
                              ? null
                              : c.str("gtxMessagingTo").trim(),
                          "text", text))));
      return OK;
    }
  }

  static final class Octopush implements Provider {
    @Override
    public String name() {
      return "octopush";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String version = c.str("octopushVersion");
      if (version == null || "2".equals(version)) {
        ctx.sender()
            .send(
                Sender.Request.post(
                    "https://api.octopush.com/v1/public/sms-campaign/send",
                    headers(
                        "api-key", c.str("octopushAPIKey"),
                        "api-login", c.str("octopushLogin"),
                        "cache-control", "no-cache",
                        "Content-Type", "application/json"),
                    Json.obj()
                        .put(
                            "recipients",
                            Json.array(
                                Json.obj()
                                    .put("phone_number", c.str("octopushPhoneNumber"))
                                    .map()))
                        .put("text", asciiOnly(msg))
                        .put("type", c.str("octopushSMSType"))
                        .put("purpose", "alert")
                        .put("sender", c.str("octopushSenderName"))
                        .toString()));
        return OK;
      }
      if ("1".equals(version)) {
        Map<String, String> params =
            fields(
                "user_login", c.str("octopushDMLogin"),
                "api_key", c.str("octopushDMAPIKey"),
                "sms_recipients", c.str("octopushDMPhoneNumber"),
                "sms_sender", c.str("octopushDMSenderName"),
                "sms_type",
                    "sms_premium".equals(c.str("octopushDMSMSType")) ? "FR" : "XXX",
                "transactional", "1",
                "sms_text", asciiOnly(msg));
        Sender.Response response =
            ctx.sender()
                .send(
                    Sender.Request.post(
                        "https://www.octopush-dm.com/api/sms/json?" + form(params),
                        headers("cache-control", "no-cache", "Content-Type", "application/json"),
                        "{}"));
        Map<String, Object> body = response.json();
        // This gateway answers two hundred whatever happened, so the failure is only visible in
        // the body.
        if (body.containsKey("error_code") && !"000".equals(String.valueOf(body.get("error_code")))) {
          throw new Exception("Octopush error " + Json.write(body));
        }
        return OK;
      }
      throw new Exception("Unknown Octopush version!");
    }
  }

  static final class Ooredoo implements Provider {
    /** How many recipients one call may name. Beyond that the list is sent in further calls. */
    private static final int MAX_RECIPIENTS_PER_REQUEST = 20;

    @Override
    public String name() {
      return "Ooredoo";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      List<String> recipients = new ArrayList<>();
      for (String raw : splitList(c.str("ooredooToNumber"), "[\\s,;]+")) {
        String normalised = normalise(raw);
        if (!normalised.isEmpty()) {
          recipients.add(normalised);
        }
      }
      if (recipients.isEmpty()) {
        throw new Exception("No valid recipient phone number was provided.");
      }
      String url =
          c.str("ooredooServerUrl", "https://o-papi1-lb01.ooredoo.mv/bulk_sms/v2");
      for (int i = 0; i < recipients.size(); i += MAX_RECIPIENTS_PER_REQUEST) {
        List<String> batch =
            recipients.subList(i, Math.min(i + MAX_RECIPIENTS_PER_REQUEST, recipients.size()));
        Sender.Response response =
            ctx.sender()
                .send(
                    Sender.Request.post(
                        url,
                        headers(
                            "Content-Type", "application/x-www-form-urlencoded",
                            "Authorization", "Bearer " + c.str("ooredooBearerToken")),
                        form(
                            fields(
                                "username", c.str("ooredooUsername"),
                                "access_key", Notify.base64(c.str("ooredooAccessKey")),
                                "message", msg,
                                "batch", String.join(" ", batch)))));
        Map<String, Object> body = response.json();
        Object code = body.get("response_code");
        boolean accepted = code != null && Double.parseDouble(String.valueOf(code)) == 0;
        if (!accepted) {
          Object reason = body.get("response_message");
          throw new Exception(
              "Ooredoo rejected the message: "
                  + (reason != null
                      ? String.valueOf(reason)
                      : "response_code=" + (code == null ? "unknown" : code)));
        }
      }
      return OK;
    }

    /** A bare seven-digit local number is given the country code the gateway serves. */
    private static String normalise(String number) {
      String stripped = number.replaceAll("[\\s+]", "");
      if (stripped.isEmpty()) {
        return "";
      }
      return stripped.matches("^\\d{7}$") ? "960" + stripped : stripped;
    }
  }

  static final class Plivo implements Provider {
    @Override
    public String name() {
      return "plivo";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String base = "https://api.plivo.com/v1/Account/" + c.str("plivoAuthID");
      Map<String, String> requestHeaders =
          headers(
              "Content-Type", "application/json",
              "Authorization", basic(c.str("plivoAuthID"), c.str("plivoAuthToken")));
      if ("call".equals(c.str("plivoMessageType"))) {
        String answerUrl = c.str("plivoAnswerUrl");
        String withMessage =
            answerUrl + (answerUrl.contains("?") ? "&" : "?") + "message=" + urlEncode(msg);
        ctx.sender()
            .send(
                Sender.Request.post(
                    base + "/Call/",
                    requestHeaders,
                    Json.obj()
                        .put("from", c.str("plivoFromNumber"))
                        .put("to", c.str("plivoToNumber"))
                        .put("answer_url", withMessage)
                        .put("answer_method", "GET")
                        .toString()));
      } else {
        ctx.sender()
            .send(
                Sender.Request.post(
                    base + "/Message/",
                    requestHeaders,
                    Json.obj()
                        .put("src", c.str("plivoFromNumber"))
                        .put("dst", c.str("plivoToNumber"))
                        .put("text", msg)
                        .toString()));
      }
      return OK;
    }
  }

  static final class PromoSms implements Provider {
    @Override
    public String name() {
      return "promosms";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      boolean longSms = c.truthy("promosmsAllowLongSMS");
      String clean = asciiOnly(msg);
      String text = clean.substring(0, Math.min(clean.length(), longSms ? 639 : 159));
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://promosms.com/api/rest/v3_2/sms",
                      headers(
                          "Content-Type", "application/json",
                          "Authorization",
                              basic(c.str("promosmsLogin"), c.str("promosmsPassword")),
                          "Accept", "text/json"),
                      Json.obj()
                          .put("recipients", Json.array(c.str("promosmsPhoneNumber")))
                          .put("text", text)
                          .put("long-sms", longSms)
                          .put("type", c.intOf("promosmsSMSType", 0))
                          .put("sender", c.str("promosmsSenderName"))
                          .toString()));
      Object inner = response.json().get("response");
      if (inner instanceof Map<?, ?> body) {
        Object status = body.get("status");
        if (status == null || Double.parseDouble(String.valueOf(status)) != 0) {
          throw new Exception("Something gone wrong. Api returned " + status + ".");
        }
      }
      return OK;
    }
  }

  static final class SerwerSms implements Provider {
    @Override
    public String name() {
      return "serwersms";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body =
          Json.obj()
              .put("username", c.str("serwersmsUsername"))
              .put("password", c.str("serwersmsPassword"))
              .put("text", asciiOnly(msg))
              .put("sender", c.str("serwersmsSenderName"));
      if ("group".equals(c.str("serwersmsRecipientType"))) {
        body.put("group_id", c.str("serwersmsGroupId"));
      } else {
        body.put("phone", c.str("serwersmsPhoneNumber"));
      }
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://api2.serwersms.pl/messages/send_sms",
                      headers("Content-Type", "application/json"),
                      body.toString()));
      Map<String, Object> answer = response.json();
      if (!Boolean.TRUE.equals(answer.get("success"))) {
        Object error = answer.get("error");
        if (error instanceof Map<?, ?> details) {
          throw new Exception(
              "SerwerSMS.pl API returned error code "
                  + details.get("code")
                  + " ("
                  + details.get("type")
                  + ") with error message: "
                  + details.get("message"));
        }
        throw new Exception("SerwerSMS.pl API returned an unexpected response");
      }
      return OK;
    }
  }

  static final class SevenIO implements Provider {
    @Override
    public String name() {
      return "SevenIO";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String text = msg;
      if (h != null) {
        String address = Notify.extractAddress(m);
        String bracketed = address.isEmpty() ? "" : "(" + address + ") ";
        if (Notify.isDown(h)) {
          text =
              "Your service "
                  + Notify.string(m, "name")
                  + " "
                  + bracketed
                  + "went down at "
                  + Notify.string(h, "localDateTime")
                  + " ("
                  + Notify.string(h, "timezone")
                  + "). Error: "
                  + Notify.string(h, "msg");
        } else if (Notify.isUp(h)) {
          text =
              "Your service "
                  + Notify.string(m, "name")
                  + " "
                  + bracketed
                  + "went back up at "
                  + Notify.string(h, "localDateTime")
                  + " ("
                  + Notify.string(h, "timezone")
                  + ").";
        }
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://gateway.seven.io/api/sms",
                  headers(
                      "Content-Type", "application/json",
                      "X-API-Key", c.str("sevenioApiKey")),
                  Json.obj()
                      .put("to", c.str("sevenioReceiver"))
                      .put("from", c.str("sevenioSender", "Uptime Kuma"))
                      .put("text", text)
                      .toString()));
      return OK;
    }
  }

  static final class Signal implements Provider {
    @Override
    public String name() {
      return "signal";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String message =
          c.truthy("signalUseTemplate")
              ? Notify.renderTemplate(c.str("signalTemplate"), msg, m, h)
              : msg;
      List<String> recipients =
          new ArrayList<>(
              List.of(
                  (c.str("signalRecipients") == null ? "" : c.str("signalRecipients"))
                      .replaceAll("\\s", "")
                      .split(",")));
      ctx.sender()
          .send(
              Sender.Request.post(
                  c.str("signalURL"),
                  headers("Content-Type", "application/json"),
                  Json.obj()
                      .put("message", message)
                      .put("number", c.str("signalNumber"))
                      .put("recipients", recipients)
                      .toString()));
      return OK;
    }
  }

  static final class SmsGateway implements Provider {
    @Override
    public String name() {
      return "SMSGateway";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      List<String> failures = new ArrayList<>();
      for (String to : splitList(c.str("smsgatewayTo"), ",")) {
        Sender.Response response =
            ctx.sender()
                .send(
                    Sender.Request.post(
                        Notify.stripTrailingSlashes(c.str("smsgatewayUrl")) + "/api/v1/sms/send",
                        headers(
                            "X-API-Key", c.str("smsgatewayApiKey"),
                            "Content-Type", "application/json"),
                        Json.obj().put("to", to).put("body", msg).toString()));
        Map<String, Object> body = response.json();
        if ("failed".equals(body.get("status"))) {
          Object reason = body.get("message");
          failures.add(to + ": " + (reason == null ? "unknown error" : reason));
        }
      }
      if (!failures.isEmpty()) {
        throw new Exception("Failed to send SMS to " + String.join("; ", failures));
      }
      return OK;
    }
  }

  static final class SmsPlanet implements Provider {
    @Override
    public String name() {
      return "SMSPlanet";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.form(
                      "https://api2.smsplanet.pl/sms",
                      headers(
                          "Authorization", "Bearer " + c.str("smsplanetApiToken"),
                          "content-type", "multipart/form-data"),
                      fields(
                          "from", c.str("smsplanetSenderName"),
                          "to", c.str("smsplanetPhoneNumbers"),
                          // Only the first, because the source substitutes without a global flag.
                          "msg", msg.replaceFirst("🔴", "❌"))));
      Map<String, Object> body = response.json();
      if (body.get("messageId") == null) {
        Object error = body.get("errorMsg");
        throw new Exception(
            error != null
                ? String.valueOf(error)
                : "SMSPlanet server did not respond with the expected result");
      }
      return OK;
    }
  }

  static final class Smsc implements Provider {
    @Override
    public String name() {
      return "smsc";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      // Assembled by hand rather than form-encoded: the source concatenates raw values and only
      // encodes the message, so a login containing a reserved character is sent as written.
      List<String> parts = new ArrayList<>();
      parts.add("fmt=3");
      parts.add("translit=" + c.str("smscTranslit"));
      parts.add("login=" + c.str("smscLogin"));
      parts.add("psw=" + c.str("smscPassword"));
      parts.add("phones=" + c.str("smscToNumber"));
      parts.add("mes=" + urlEncode(asciiOnly(msg)));
      if (c.str("smscSenderName") != null && !c.str("smscSenderName").isEmpty()) {
        parts.add("sender=" + c.str("smscSenderName"));
      }
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.get(
                      "https://smsc.kz/sys/send.php?" + String.join("&", parts),
                      headers("Content-Type", "application/json", "Accept", "text/json")));
      Map<String, Object> body = response.json();
      if (body.get("id") == null) {
        throw new Exception(
            "Something gone wrong. Api returned code "
                + body.get("error_code")
                + ": "
                + body.get("error"));
      }
      return OK;
    }
  }

  static final class SmsEagle implements Provider {
    @Override
    public String name() {
      return "SMSEagle";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String api = c.str("smseagleApiType");
      if ("smseagle-apiv1".equals(api)) {
        return sendV1(c, msg, ctx);
      }
      if ("smseagle-apiv2".equals(api)) {
        return sendV2(c, msg, ctx);
      }
      // Neither version selected: the source composes nothing and answers nothing.
      return null;
    }

    private String sendV1(Config c, String msg, Context ctx) throws Exception {
      String recipientType;
      String sendMethod;
      Integer voiceId = null;
      Integer duration = null;
      switch (String.valueOf(c.str("smseagleRecipientType"))) {
        case "smseagle-contact" -> {
          recipientType = "contactname";
          sendMethod = "/send_tocontact";
        }
        case "smseagle-group" -> {
          recipientType = "groupname";
          sendMethod = "/send_togroup";
        }
        case "smseagle-to" -> {
          recipientType = "to";
          sendMethod = "/send_sms";
          if (!"smseagle-sms".equals(c.str("smseagleMsgType"))) {
            duration = c.intOf("smseagleDuration", 10);
            switch (String.valueOf(c.str("smseagleMsgType"))) {
              case "smseagle-ring" -> sendMethod = "/ring_call";
              case "smseagle-tts" -> sendMethod = "/tts_call";
              case "smseagle-tts-advanced" -> {
                sendMethod = "/tts_adv_call";
                voiceId = c.intOf("smseagleTtsModel", 1);
              }
              default -> {
                // No further call kind: the send method stays the plain message one.
              }
            }
          }
        }
        default -> {
          recipientType = "undefined";
          sendMethod = "undefined";
        }
      }
      Map<String, String> params = new LinkedHashMap<>();
      params.put("access_token", c.str("smseagleToken"));
      params.put(recipientType, c.str("smseagleRecipient"));
      String recipientKind = c.str("smseagleRecipientType");
      if (recipientKind == null || "smseagle-sms".equals(recipientKind)) {
        params.put("unicode", c.truthy("smseagleEncoding") ? "1" : "0");
        params.put("highpriority", c.str("smseaglePriority", "0"));
      } else {
        params.put("duration", String.valueOf(duration));
      }
      if (!"smseagle-ring".equals(recipientKind)) {
        params.put("message", msg);
      }
      if (voiceId != null) {
        params.put("voice_id", String.valueOf(voiceId));
      }
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.get(
                      c.str("smseagleUrl") + "/http_api" + sendMethod + "?" + form(params),
                      headers("Content-Type", "application/x-www-form-urlencoded")));
      if (response.body() == null || !response.body().contains("OK")) {
        throw new Exception("SMSEagle API returned error: " + response.body());
      }
      return OK;
    }

    private String sendV2(Config c, String msg, Context ctx) throws Exception {
      String endpoint =
          switch (String.valueOf(c.str("smseagleMsgType"))) {
            case "smseagle-ring" -> "/calls/ring";
            case "smseagle-tts" -> "/calls/tts";
            case "smseagle-tts-advanced" -> "/calls/tts_advanced";
            default -> "/messages/sms";
          };
      Json.Obj body =
          Json.obj()
              .put("text", msg)
              .put("encoding", c.truthy("smseagleEncoding") ? "unicode" : "standard")
              .put("priority", c.intOf("smseaglePriority", 0));
      if (c.truthy("smseagleRecipientContact")) {
        body.put("contacts", numbers(c.str("smseagleRecipientContact")));
      }
      if (c.truthy("smseagleRecipientGroup")) {
        body.put("groups", numbers(c.str("smseagleRecipientGroup")));
      }
      if (c.truthy("smseagleRecipientTo")) {
        body.put("to", List.of(c.str("smseagleRecipientTo").split(",")));
      }
      if (!"smseagle-sms".equals(c.str("smseagleMsgType"))) {
        body.put("duration", c.intOf("smseagleDuration", 10));
        if ("smseagle-tts-advanced".equals(c.str("smseagleMsgType"))) {
          body.put("voice_id", c.intOf("smseagleTtsModel", 1));
        }
      }
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      c.str("smseagleUrl") + "/api/v2" + endpoint,
                      headers(
                          "access-token", c.str("smseagleToken"),
                          "Content-Type", "application/json"),
                      body.toString()));
      List<?> rows;
      try {
        rows = Json.MAPPER.readValue(response.body(), List.class);
      } catch (Exception e) {
        rows = List.of();
      }
      long queued =
          rows.stream()
              .filter(row -> row instanceof Map<?, ?> entry && "queued".equals(entry.get("status")))
              .count();
      if (response.status() != 200 || queued == 0) {
        if (rows.isEmpty()) {
          throw new Exception("SMSEagle API returned an empty response");
        }
        throw new Exception("SMSEagle API returned error: " + response.body());
      }
      long unqueued = rows.size() - queued;
      if (unqueued > 0) {
        return "Sent " + queued + "/" + rows.size() + " Messages Successfully.";
      }
      return OK;
    }

    private static List<Integer> numbers(String csv) {
      List<Integer> out = new ArrayList<>();
      for (String part : csv.split(",")) {
        out.add(Integer.valueOf(part.trim()));
      }
      return out;
    }
  }

  static final class SmsIr implements Provider {
    /** The template parameter this gateway carries has a twenty-character limit. */
    private static final int MAX_MESSAGE_LENGTH = 20;

    @Override
    public String name() {
      return "smsir";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String text = msg;
      if (text.length() > MAX_MESSAGE_LENGTH) {
        text = text.replaceAll("\\s", "");
      }
      if (text.length() > MAX_MESSAGE_LENGTH) {
        text = text.substring(0, MAX_MESSAGE_LENGTH - 1 - 3) + "...";
      }
      for (String raw : String.valueOf(c.str("smsirNumber")).split(",")) {
        String mobile = normalise(raw);
        ctx.sender()
            .send(
                Sender.Request.post(
                    "https://api.sms.ir/v1/send/verify",
                    headers(
                        "Content-Type", "application/json",
                        "Accept", "application/json",
                        "X-API-Key", c.str("smsirApiKey")),
                    Json.obj()
                        .put("mobile", mobile)
                        .put("templateId", c.intOf("smsirTemplate", 0))
                        .put(
                            "parameters",
                            Json.array(
                                Json.obj().put("name", "uptkumaalert").put("value", text).map()))
                        .toString()));
      }
      return OK;
    }

    /** A national number written with its leading zero is sent without it. */
    private static String normalise(String mobile) {
      if (mobile.length() == 11 && mobile.startsWith("09")) {
        try {
          if (String.valueOf(Long.parseLong(mobile)).equals(mobile.substring(1))) {
            return mobile.substring(1);
          }
        } catch (NumberFormatException e) {
          return mobile;
        }
      }
      return mobile;
    }
  }

  static final class SmsManager implements Provider {
    @Override
    public String name() {
      return "SMSManager";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      // The gateway parameter reads a key the request object does not carry, so the literal word
      // is what goes on the wire. Reproduced: a monitor moved from the source keeps the same
      // gateway selection, which is to say none.
      String url =
          "https://http-api.smsmanager.cz/Send?apikey="
              + c.str("smsmanagerApiKey")
              + "&message="
              + asciiOnly(msg)
              + "&number="
              + c.str("numbers")
              + "&gateway=undefined";
      ctx.sender().send(Sender.Request.get(url, headers()));
      return OK;
    }
  }

  static final class SmsPartner implements Provider {
    @Override
    public String name() {
      return "SMSPartner";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String clean = asciiOnly(msg);
      String sender = c.str("smspartnerSenderName", "");
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://api.smspartner.fr/v1/send",
                      headers(
                          "Content-Type", "application/json",
                          "cache-control", "no-cache",
                          "Accept", "application/json"),
                      Json.obj()
                          .put("apiKey", c.str("smspartnerApikey"))
                          .put("sender", sender.substring(0, Math.min(sender.length(), 11)))
                          .put("phoneNumbers", c.str("smspartnerPhoneNumber"))
                          .put("message", clean.substring(0, Math.min(clean.length(), 639)))
                          .toString()));
      Map<String, Object> body = response.json();
      if (!Boolean.TRUE.equals(body.get("success"))) {
        Object inner = body.get("response");
        Object status = inner instanceof Map<?, ?> map ? map.get("status") : null;
        throw new Exception("Api returned " + status + ".");
      }
      return OK;
    }
  }

  static final class Telnyx implements Provider {
    @Override
    public String name() {
      return "telnyx";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body =
          Json.obj()
              .put("from", c.str("telnyxPhoneNumber"))
              .put("to", c.str("telnyxToNumber"))
              .put("text", msg)
              .putIfPresent("messaging_profile_id", c.str("telnyxMessagingProfileId"));
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.telnyx.com/v2/messages",
                  headers(
                      "Content-Type", "application/json",
                      "Authorization", "Bearer " + c.str("telnyxApiKey")),
                  body.toString()));
      return OK;
    }
  }

  static final class Teltonika implements Provider {
    @Override
    public String name() {
      return "Teltonika";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String origin;
      try {
        java.net.URI uri = java.net.URI.create(c.str("teltonikaUrl"));
        origin =
            uri.getScheme()
                + "://"
                + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
      } catch (Exception e) {
        throw new Exception("Invalid URL: " + c.str("teltonikaUrl"));
      }
      Map<String, String> requestHeaders =
          headers(
              "Content-Type", "application/json",
              "cache-control", "no-cache",
              "Accept", "application/json");
      Sender.Response login =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      origin + "/api/login",
                      requestHeaders,
                      Json.obj()
                          .put("username", c.str("teltonikaUsername"))
                          .put("password", c.str("teltonikaPassword"))
                          .toString()));
      Map<String, Object> loginBody = login.json();
      if (!Boolean.TRUE.equals(loginBody.get("success"))) {
        throw new Exception("Login failed: " + errorOf(loginBody));
      }
      Object data = loginBody.get("data");
      Object token = data instanceof Map<?, ?> map ? map.get("token") : null;
      Map<String, String> authorised = new LinkedHashMap<>(requestHeaders);
      authorised.put("Authorization", "Bearer " + token);
      Sender.Response sms =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      origin + "/api/messages/actions/send",
                      authorised,
                      Json.obj()
                          .put(
                              "data",
                              Json.obj()
                                  .put("modem", c.str("teltonikaModem"))
                                  .put("number", c.str("teltonikaPhoneNumber"))
                                  // A single message is a hundred and sixty characters.
                                  .put("message", msg.substring(0, Math.min(msg.length(), 159)))
                                  .map())
                          .toString()));
      if (!Boolean.TRUE.equals(sms.json().get("success"))) {
        // The source passes the reason as a second argument, which an Error constructor ignores,
        // so the message a person sees carries no reason at all.
        throw new Exception("Api returned: ");
      }
      return OK;
    }

    private static Object errorOf(Map<String, Object> body) {
      Object errors = body.get("errors");
      return errors instanceof Map<?, ?> map ? map.get("error") : errors;
    }
  }

  static final class Threema implements Provider {
    @Override
    public String name() {
      return "threema";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String recipientKey =
          switch (String.valueOf(c.str("threemaRecipientType"))) {
            case "identity" -> "to";
            case "phone" -> "phone";
            case "email" -> "email";
            default ->
                throw new Exception(
                    "Unsupported recipient type: " + c.str("threemaRecipientType"));
          };
      Map<String, String> body =
          fields(
              "from", c.str("threemaSenderIdentity"),
              "secret", c.str("threemaSecret"),
              "text", msg,
              recipientKey, c.str("threemaRecipient"));
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://msgapi.threema.ch/send_simple",
                      headers(
                          "Accept", "*/*",
                          "Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
                      form(body)));
      if (!response.ok()) {
        throw new Exception(apiError(response.status()));
      }
      return "Threema notification sent successfully.";
    }

    /** The gateway reports why in the status code alone, so each one is given its own sentence. */
    private static String apiError(int status) {
      return switch (status) {
        case 400 -> "Invalid recipient identity or account not set up for basic mode (400).";
        case 401 -> "Incorrect API identity or secret (401).";
        case 402 -> "No credits remaining (402).";
        case 404 -> "Recipient not found (404).";
        case 413 -> "Message is too long (413).";
        case 500 -> "Temporary internal server error (500).";
        default -> "Request failed with status " + status;
      };
    }
  }

  static final class Twilio implements Provider {
    @Override
    public String name() {
      return "twilio";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String user =
          c.truthy("twilioApiKey") ? c.str("twilioApiKey") : c.str("twilioAccountSID");
      Map<String, String> body =
          fields(
              "To", c.str("twilioToNumber"),
              "From", c.str("twilioFromNumber"),
              "Body", msg);
      if (c.truthy("twilioMessagingServiceSID")) {
        body.put("MessagingServiceSid", c.str("twilioMessagingServiceSID"));
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.twilio.com/2010-04-01/Accounts/"
                      + c.str("twilioAccountSID")
                      + "/Messages.json",
                  headers(
                      "Content-Type", "application/x-www-form-urlencoded;charset=utf-8",
                      "Authorization", basic(user, c.str("twilioAuthToken"))),
                  form(body)));
      return OK;
    }
  }

  static final class AliyunSms implements Provider {
    @Override
    public String name() {
      return "AliyunSMS";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj template = Json.obj();
      if (h != null) {
        template
            .put("name", Notify.string(m, "name"))
            .put("time", Notify.string(h, "localDateTime"))
            .put("status", statusWord(Notify.status(h)));
        if (c.truthy("optionalParameters")) {
          template.put("msg", redact(Notify.string(h, "msg")));
        }
      } else {
        template.put("name", "").put("time", "").put("status", "");
        if (c.truthy("optionalParameters")) {
          template.put("msg", redact(msg));
        }
      }

      Map<String, String> params = new LinkedHashMap<>();
      params.put("PhoneNumbers", c.str("phonenumber"));
      params.put("TemplateCode", c.str("templateCode"));
      params.put("SignName", c.str("signName"));
      params.put("TemplateParam", template.toString());
      params.put("AccessKeyId", c.str("accessKeyId"));
      params.put("Format", "JSON");
      params.put("SignatureMethod", "HMAC-SHA1");
      params.put("SignatureVersion", "1.0");
      params.put("SignatureNonce", String.valueOf(Math.random()));
      params.put("Timestamp", java.time.Instant.now().toString());
      params.put("Action", "SendSms");
      params.put("Version", "2017-05-25");
      params.put("Signature", sign(params, c.str("secretAccessKey")));

      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "http://dysmsapi.aliyuncs.com/",
                      headers("Content-Type", "application/x-www-form-urlencoded"),
                      form(params)));
      Map<String, Object> body = response.json();
      if (!"OK".equals(body.get("Message"))) {
        throw new Exception(String.valueOf(body.get("Message")));
      }
      return OK;
    }

    private static String statusWord(Integer status) {
      if (status == null) {
        return "";
      }
      return status == Notify.DOWN ? "DOWN" : status == Notify.UP ? "UP" : String.valueOf(status);
    }

    /**
     * Blank out anything in the message that identifies a machine.
     *
     * <p>The gateway is a public carrier and the template parameter is stored on its side, so a
     * hostname or address in a failure message would leave the operator's network. The order the
     * substitutions run in matters: a URL is matched before the address inside it is.
     */
    static String redact(String message) {
      if (message == null || message.isEmpty()) {
        return message;
      }
      String out = message.replaceAll("(?i)(?:https?|ftp|ws|wss)://[^\\s]+", "[URL]");
      out = out.replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b", "[IP]");
      out =
          out.replaceAll(
              "\\[?(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\]?(?::\\d+)?", "[IP]");
      out =
          out.replaceAll(
              "\\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(?::\\d+)?\\b",
              "[Domain]");
      out = out.replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}\\b", "[CIDR]");
      return out;
    }

    /** The gateway's own canonicalisation, which differs from ordinary form encoding. */
    static String sign(Map<String, String> params, String secret) {
      List<String> keys = new ArrayList<>(params.keySet());
      keys.remove("Signature");
      java.util.Collections.sort(keys);
      StringBuilder canonical = new StringBuilder();
      for (String key : keys) {
        if (canonical.length() > 0) {
          canonical.append('&');
        }
        canonical.append(percent(key)).append('=').append(percent(params.get(key)));
      }
      String toSign = "POST&" + percent("/") + "&" + percent(canonical.toString());
      return Notify.hmacBase64(
          "HmacSHA1", (secret + "&").getBytes(java.nio.charset.StandardCharsets.UTF_8), toSign);
    }

    private static String percent(String value) {
      return urlEncode(value)
          .replace("!", "%21")
          .replace("*", "%2A")
          .replace("'", "%27")
          .replace("(", "%28")
          .replace(")", "%29");
    }
  }

  static final class CallMeBot implements Provider {
    @Override
    public String name() {
      return "CallMeBot";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String endpoint = c.str("callMeBotEndpoint");
      // The message replaces any text already in the endpoint rather than being appended to it.
      String stripped = endpoint.replaceAll("([?&])text=[^&]*", "$1");
      stripped = stripped.replaceAll("[?&]$", "");
      ctx.sender()
          .send(
              Sender.Request.get(
                  stripped + (stripped.contains("?") ? "&" : "?") + "text=" + urlEncode(msg),
                  headers()));
      return OK;
    }
  }
}
