package io.akka.uptimekuma.api;

import java.util.List;

/**
 * The snippets the push monitor's own screen offers, and the titles the game monitor lists.
 *
 * <p>The snippets are what a person copies to make something beat into a push monitor, so the
 * placeholder in each is the URL this server answers on rather than the source's.
 */
public final class PushExamples {

  private PushExamples() {}

  /** Every title, as the game monitor's dropdown lists them: key first, then what it is called. */
  public static final List<String[]> GAMES = load();

  private static List<String[]> load() {
    try (var in = PushExamples.class.getResourceAsStream("/gamedig-games.json")) {
      List<?> rows = io.akka.uptimekuma.notifications.Json.MAPPER.readValue(in, List.class);
      java.util.List<String[]> out = new java.util.ArrayList<>();
      for (Object row : rows) {
        if (row instanceof java.util.Map<?, ?> entry) {
          out.add(
              new String[] {String.valueOf(entry.get("key")), String.valueOf(entry.get("pretty"))});
        }
      }
      out.sort(java.util.Comparator.comparing(entry -> entry[1]));
      return List.copyOf(out);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read the game table", e);
    }
  }

  public static String forLanguage(String language) {
    return switch (language) {
      case "bash-curl" ->
          """
          #!/bin/sh
          # Call this on a schedule; a push monitor goes down when nothing calls it in time.
          while true; do
              curl -fsS -o /dev/null "$PUSH_URL"
              sleep 60
          done
          """;
      case "javascript-fetch" ->
          """
          // Call this on a schedule; a push monitor goes down when nothing calls it in time.
          setInterval(async () => {
              await fetch(process.env.PUSH_URL);
          }, 60 * 1000);
          """;
      case "typescript-fetch" ->
          """
          // Call this on a schedule; a push monitor goes down when nothing calls it in time.
          setInterval(async (): Promise<void> => {
              await fetch(process.env.PUSH_URL as string);
          }, 60 * 1000);
          """;
      case "python" ->
          """
          import time
          import urllib.request

          # Call this on a schedule; a push monitor goes down when nothing calls it in time.
          while True:
              urllib.request.urlopen(PUSH_URL)
              time.sleep(60)
          """;
      case "php" ->
          """
          <?php
          // Call this on a schedule; a push monitor goes down when nothing calls it in time.
          while (true) {
              file_get_contents($pushUrl);
              sleep(60);
          }
          """;
      case "go" ->
          """
          package main

          import (
              "net/http"
              "time"
          )

          // Call this on a schedule; a push monitor goes down when nothing calls it in time.
          func main() {
              for {
                  http.Get(pushURL)
                  time.Sleep(60 * time.Second)
              }
          }
          """;
      case "java" ->
          """
          import java.net.URI;
          import java.net.http.HttpClient;
          import java.net.http.HttpRequest;
          import java.net.http.HttpResponse;

          // Call this on a schedule; a push monitor goes down when nothing calls it in time.
          public class Push {
              public static void main(String[] args) throws Exception {
                  HttpClient client = HttpClient.newHttpClient();
                  while (true) {
                      client.send(
                          HttpRequest.newBuilder(URI.create(args[0])).GET().build(),
                          HttpResponse.BodyHandlers.discarding());
                      Thread.sleep(60_000);
                  }
              }
          }
          """;
      case "csharp" ->
          """
          using System;
          using System.Net.Http;
          using System.Threading;

          // Call this on a schedule; a push monitor goes down when nothing calls it in time.
          var client = new HttpClient();
          while (true)
          {
              await client.GetAsync(pushUrl);
              Thread.Sleep(60 * 1000);
          }
          """;
      case "powershell" ->
          """
          # Call this on a schedule; a push monitor goes down when nothing calls it in time.
          while ($true) {
              Invoke-WebRequest -Uri $pushUrl -UseBasicParsing | Out-Null
              Start-Sleep -Seconds 60
          }
          """;
      case "docker" ->
          """
          # A container whose only job is to beat.
          # Call this on a schedule; a push monitor goes down when nothing calls it in time.
          docker run -d --restart=always --name uptime-kuma-push \\
              curlimages/curl:latest \\
              sh -c 'while true; do curl -fsS -o /dev/null "$PUSH_URL"; sleep 60; done'
          """;
      default -> null;
    };
  }
}
