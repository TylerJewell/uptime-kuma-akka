package io.akka.uptimekuma.notifications;

import static io.akka.uptimekuma.notifications.Notify.OK;
import static io.akka.uptimekuma.notifications.Notify.headers;
import static io.akka.uptimekuma.notifications.Notify.string;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The targets that deliver an email. */
final class EmailProviders {

  private EmailProviders() {}

  static List<Provider> all() {
    return List.of(new Brevo(), new SendGrid(), new Resend(), new TurboSmtp(), new Smtp());
  }

  /** Turn a comma-separated address field into the list-of-objects shape two of these want. */
  private static List<Object> addresses(String csv) {
    List<Object> out = new ArrayList<>();
    for (String address : Notify.splitList(csv, ",")) {
      out.add(Json.obj().put("email", address).map());
    }
    return out;
  }

  static final class Brevo implements Provider {
    @Override
    public String name() {
      return "Brevo";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj body =
          Json.obj()
              .put(
                  "sender",
                  Json.obj()
                      .put("email", c.str("brevoFromEmail").trim())
                      .put("name", c.str("brevoFromName", "Uptime Kuma"))
                      .map())
              .put("to", Json.array(Json.obj().put("email", c.str("brevoToEmail")).map()))
              .put("subject", c.str("brevoSubject", "Notification from Your Uptime Kuma"))
              .put(
                  "htmlContent",
                  "<html><head></head><body><p>" + msg.replace("\n", "<br>") + "</p></body></html>");
      if (c.truthy("brevoCcEmail")) {
        body.put("cc", addresses(c.str("brevoCcEmail")));
      }
      if (c.truthy("brevoBccEmail")) {
        body.put("bcc", addresses(c.str("brevoBccEmail")));
      }
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://api.brevo.com/v3/smtp/email",
                      headers(
                          "Accept", "application/json",
                          "Content-Type", "application/json",
                          "api-key", c.str("brevoApiKey")),
                      body.toString()));
      if (response.status() != 201) {
        throw new Exception("Unexpected status code: " + response.status());
      }
      return OK;
    }
  }

  static final class SendGrid implements Provider {
    @Override
    public String name() {
      return "SendGrid";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Json.Obj personalisation =
          Json.obj().put("to", Json.array(Json.obj().put("email", c.str("sendgridToEmail")).map()));
      if (c.truthy("sendgridCcEmail")) {
        personalisation.put("cc", addresses(c.str("sendgridCcEmail")));
      }
      if (c.truthy("sendgridBccEmail")) {
        personalisation.put("bcc", addresses(c.str("sendgridBccEmail")));
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://api.sendgrid.com/v3/mail/send",
                  headers(
                      "Content-Type", "application/json",
                      "Authorization", "Bearer " + c.str("sendgridApiKey")),
                  Json.obj()
                      .put("personalizations", Json.array(personalisation.map()))
                      .put(
                          "from",
                          Json.obj().put("email", c.str("sendgridFromEmail").trim()).map())
                      .put("subject", c.str("sendgridSubject", "Notification from Your Uptime Kuma"))
                      .put(
                          "content",
                          Json.array(
                              Json.obj().put("type", "text/plain").put("value", msg).map()))
                      .toString()));
      return OK;
    }
  }

  static final class Resend implements Provider {
    @Override
    public String name() {
      return "Resend";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      Sender.Response response =
          ctx.sender()
              .send(
                  Sender.Request.post(
                      "https://api.resend.com/emails",
                      headers(
                          "Authorization", "Bearer " + c.str("resendApiKey"),
                          "Content-Type", "application/json"),
                      Json.obj()
                          .put(
                              "from",
                              c.str("resendFromName", "Uptime Kuma")
                                  + " <"
                                  + c.str("resendFromEmail").trim()
                                  + ">")
                          .put("to", c.str("resendToEmail"))
                          .put("subject", c.str("resendSubject", "Notification from Your Uptime Kuma"))
                          .put("text", msg)
                          .toString()));
      if (response.status() != 200) {
        throw new Exception("Unexpected status code: " + response.status());
      }
      return OK;
    }
  }

  static final class TurboSmtp implements Provider {
    @Override
    public String name() {
      return "TurboSMTP";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String host =
          "eu".equals(c.str("turbosmtpRegion"))
              ? "api.eu.turbo-smtp.com"
              : "api.turbo-smtp.com";
      Json.Obj body =
          Json.obj()
              .put("from", c.str("turbosmtpFromEmail").trim())
              .put("to", c.str("turbosmtpToEmail"))
              .put("subject", c.str("turbosmtpSubject", "Notification from Your Uptime Kuma"))
              .put("content", msg);
      if (c.truthy("turbosmtpCcEmail")) {
        body.put("cc", String.join(",", Notify.splitList(c.str("turbosmtpCcEmail"), ",")));
      }
      if (c.truthy("turbosmtpBccEmail")) {
        body.put("bcc", String.join(",", Notify.splitList(c.str("turbosmtpBccEmail"), ",")));
      }
      ctx.sender()
          .send(
              Sender.Request.post(
                  "https://" + host + "/api/v2/mail/send",
                  headers(
                      "Content-Type", "application/json",
                      "consumerKey", c.str("turbosmtpConsumerKey"),
                      "consumerSecret", c.str("turbosmtpConsumerSecret")),
                  body.toString()));
      return OK;
    }
  }

  /**
   * The one target that talks to a mail server rather than to somebody's HTTP API.
   *
   * <p>Composed here and handed to the {@link Sender} as a request whose body is the message the
   * server would be given, so what it composed can be compared the same way every other target's
   * request is. {@link SmtpDelivery} is what actually speaks the protocol.
   */
  static final class Smtp implements Provider {
    @Override
    public String name() {
      return "smtp";
    }

    @Override
    public String send(
        Config c, String msg, Map<String, Object> m, Map<String, Object> h, Context ctx)
        throws Exception {
      String subject = msg;
      String body = msg;
      boolean html = false;
      if (h != null) {
        body = msg + "\nTime (" + string(h, "timezone") + "): " + string(h, "localDateTime");
      }
      if ((m != null && h != null) || msg.endsWith("Testing")) {
        String customSubject = c.str("customSubject");
        if (customSubject != null && !customSubject.trim().isEmpty()) {
          subject = Notify.renderTemplate(customSubject.trim(), msg, m, h);
        }
        String customBody = c.str("customBody");
        if (customBody != null && !customBody.trim().isEmpty()) {
          html = c.truthy("htmlBody");
          body = Notify.renderTemplate(customBody.trim(), msg, m, h);
        }
      }

      Json.Obj mail =
          Json.obj()
              .put("host", c.str("smtpHost"))
              .put("port", c.intOf("smtpPort", 25))
              .put("secure", c.truthy("smtpSecure"))
              .put("ignoreTLS", !c.truthy("smtpSecure") && c.truthy("smtpIgnoreSTARTTLS"))
              .put("rejectUnauthorized", !c.truthy("smtpIgnoreTLSError"))
              .put("username", c.str("smtpUsername"))
              .put("password", c.str("smtpPassword"))
              .put("from", c.str("smtpFrom"))
              .put("to", c.str("smtpTo"))
              .put("cc", c.str("smtpCC"))
              .put("bcc", c.str("smtpBCC"))
              .put("subject", subject)
              .put(html ? "html" : "text", body);
      if (c.truthy("smtpAdditionalHeaders")) {
        try {
          mail.put(
              "headers", Json.MAPPER.readValue(c.str("smtpAdditionalHeaders"), Map.class));
        } catch (Exception e) {
          throw new Exception("Additional Headers is not a valid JSON");
        }
      }
      // A mail server is not an HTTP endpoint, so the scheme says which protocol the sender
      // should speak and the body carries everything the message needs.
      ctx.sender()
          .send(
              new Sender.Request(
                  "SMTP",
                  "smtp://" + c.str("smtpHost") + ":" + c.intOf("smtpPort", 25),
                  headers(),
                  mail.toString(),
                  null));
      return OK;
    }
  }
}
