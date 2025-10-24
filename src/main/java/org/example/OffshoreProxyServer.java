package org.example;

import java.io.*;
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
        ServerSocket serverSocket = new ServerSocket(LISTEN_PORT);
        System.out.println("OffshoreProxy listening on port " + LISTEN_PORT);

        // Accept only one persistent connection from ShipProxy
        Socket shipSocket = serverSocket.accept();
        System.out.println("ShipProxy connected: " + shipSocket.getRemoteSocketAddress());

        InputStream shipIn = shipSocket.getInputStream();
        OutputStream shipOut = shipSocket.getOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(shipIn));
        HttpClient httpClient = HttpClient.newBuilder().build();

        while (true) {
            try {
                // --- 1. Read full HTTP request from ShipProxy ---
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                String requestLine = reader.readLine();
                if (requestLine == null) break; // connection closed
                baos.write((requestLine + "\r\n").getBytes());

                // Read headers
                int contentLength = 0;
                String line;
                while (!(line = reader.readLine()).isEmpty()) {
                    baos.write((line + "\r\n").getBytes());
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    }
                }
                baos.write("\r\n".getBytes()); // end of headers

                // Read body if present
                byte[] body = new byte[contentLength];
                if (contentLength > 0) {
                    int read = 0;
                    while (read < contentLength) {
                        int r = shipIn.read(body, read, contentLength - read);
                        if (r == -1) break;
                        read += r;
                    }
                    baos.write(body);
                }

                byte[] fullRequestBytes = baos.toByteArray();

                // --- 2. Parse request line ---
                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String url = parts[1];

                HttpRequest.Builder builder = HttpRequest.newBuilder().uri(new URI(url));
                switch (method.toUpperCase()) {
                    case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
                    case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
                    case "DELETE" -> builder.DELETE();
                    default -> builder.GET();
                }

                // --- 3. Send request to target server ---
                HttpRequest httpRequest = builder.build();
                HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

                // --- 4. Build HTTP/1.1 response ---
                StringBuilder responseHeaders = new StringBuilder();
                responseHeaders.append("HTTP/1.1 ").append(response.statusCode()).append(" OK\r\n");
                response.headers().map().forEach((k, v) -> responseHeaders.append(k).append(": ")
                        .append(String.join(",", v)).append("\r\n"));
                responseHeaders.append("Content-Length: ").append(response.body().length).append("\r\n");
                responseHeaders.append("\r\n");

                // --- 5. Send headers + body back to ShipProxy ---
                shipOut.write(responseHeaders.toString().getBytes());
                shipOut.write(response.body());
                shipOut.flush();

            } catch (Exception e) {
                e.printStackTrace();
                break; // break loop on error
            }
        }

        shipSocket.close();
        serverSocket.close();
    }
}