package org.example;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class OffshoreProxyServer {
    private static final int LISTEN_PORT = 8083;

    public static void main(String[] args) throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(LISTEN_PORT)) {
            System.out.println("OffshoreProxy listening on port " + LISTEN_PORT);

            Socket shipSocket = serverSocket.accept();
            System.out.println("ShipProxy connected: " + shipSocket.getRemoteSocketAddress());

            BufferedReader reader = new BufferedReader(new InputStreamReader(shipSocket.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(shipSocket.getOutputStream()));

            HttpClient httpClient = HttpClient.newHttpClient();

            String requestLine;
            while ((requestLine = reader.readLine()) != null) {
                if (requestLine.isEmpty()) continue;

                System.out.println("Received request: " + requestLine);

                try {
                    // Minimal parsing for request like: "GET http://example.com"
                    String[] parts = requestLine.split(" ");
                    String method = parts[0];
                    String url = parts[1];

                    HttpRequest.Builder builder = HttpRequest.newBuilder().uri(new URI(url));
                    switch (method.toUpperCase()) {
                        case "GET" -> builder.GET();
                        case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody());
                        case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.noBody());
                        case "DELETE" -> builder.DELETE();
                        default -> builder.GET();
                    }

                    HttpRequest httpRequest = builder.build();
                    HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                    String body = response.body();
                    String headers =
                            "HTTP/1.1 " + response.statusCode() + " OK\r\n" +
                                    "Content-Type: text/html; charset=UTF-8\r\n" +
                                    "Content-Length: " + body.getBytes().length + "\r\n" +
                                    "\r\n";

                    writer.write(headers);
                    writer.write(body);
                    writer.flush();

                } catch (Exception e) {
                    writer.write("HTTP/1.1 500 Internal Server Error\r\n\r\n" + e.getMessage());
                    writer.flush();
                }
            }
        }
    }
}