package org.example;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class OffshoreProxyServer {
    private static final int LISTEN_PORT = 8083;

    public static void main(String[] args) throws Exception {
        ServerSocket serverSocket = new ServerSocket(LISTEN_PORT);
        System.out.println("OffshoreProxy listening on port " + LISTEN_PORT);

        Socket shipSocket = serverSocket.accept();
        System.out.println("ShipProxy connected: " + shipSocket.getRemoteSocketAddress());

        shipSocket.setTcpNoDelay(true);
        shipSocket.setKeepAlive(true);

        InputStream shipIn = shipSocket.getInputStream();
        OutputStream shipOut = shipSocket.getOutputStream();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        while (true) {
            try {
                System.out.println("\n=== Waiting for request from ship ===");

                // Read request
                RequestData requestData = readRequest(shipIn);
                if (requestData == null) {
                    System.out.println("Connection closed by ship proxy");
                    break;
                }

                System.out.println("Received: " + requestData.method + " " + requestData.url);
                System.out.println("Headers count: " + requestData.headers.size());
                System.out.println("Body size: " + requestData.body.length + " bytes");

                // Build HTTP request
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(new URI(requestData.url))
                        .timeout(Duration.ofSeconds(30));

                // Set method and body
                HttpRequest.BodyPublisher bodyPublisher;
                if (requestData.body.length > 0) {
                    bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(requestData.body);
                } else {
                    bodyPublisher = HttpRequest.BodyPublishers.noBody();
                }

                switch (requestData.method.toUpperCase()) {
                    case "POST":
                        builder.POST(bodyPublisher);
                        break;
                    case "PUT":
                        builder.PUT(bodyPublisher);
                        break;
                    case "DELETE":
                        builder.DELETE();
                        break;
                    case "PATCH":
                        builder.method("PATCH", bodyPublisher);
                        break;
                    case "HEAD":
                        builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                        break;
                    case "OPTIONS":
                        builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
                        break;
                    default:
                        builder.GET();
                }

                // Forward headers (skip hop-by-hop headers)
                for (String header : requestData.headers) {
                    if (header.isEmpty()) continue;

                    String[] parts = header.split(":", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim().toLowerCase();
                        String value = parts[1].trim();

                        // Skip hop-by-hop and problematic headers
                        if (!key.equals("connection") &&
                                !key.equals("keep-alive") &&
                                !key.equals("proxy-connection") &&
                                !key.equals("transfer-encoding") &&
                                !key.equals("te") &&
                                !key.equals("trailer") &&
                                !key.equals("upgrade") &&
                                !key.equals("host")) {  // Host is set automatically by HttpClient
                            try {
                                builder.header(parts[0].trim(), value);
                            } catch (Exception e) {
                                System.err.println("Skipping invalid header: " + header);
                            }
                        }
                    }
                }

                // Make the HTTP request
                System.out.println("Making HTTP request to: " + requestData.url);
                HttpRequest httpRequest = builder.build();
                HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

                System.out.println("Received response: " + response.statusCode());
                System.out.println("Response body size: " + response.body().length + " bytes");

                // Build response
                StringBuilder respHeaders = new StringBuilder();
                respHeaders.append("HTTP/1.1 ").append(response.statusCode()).append(" ");
                respHeaders.append(getReasonPhrase(response.statusCode())).append("\r\n");

                // Forward response headers
                response.headers().map().forEach((k, v) -> {
                    String key = k.toLowerCase();
                    // Skip hop-by-hop headers
                    if (!key.equals("connection") &&
                            !key.equals("keep-alive") &&
                            !key.equals("transfer-encoding")) {
                        for (String val : v) {
                            respHeaders.append(k).append(": ").append(val).append("\r\n");
                        }
                    }
                });

                // Add Content-Length and Connection headers
                respHeaders.append("Content-Length: ").append(response.body().length).append("\r\n");
                respHeaders.append("Connection: keep-alive\r\n");
                respHeaders.append("\r\n");

                // Send response
                System.out.println("Sending response headers (" + respHeaders.length() + " bytes)");
                shipOut.write(respHeaders.toString().getBytes());

                System.out.println("Sending response body (" + response.body().length + " bytes)");
                shipOut.write(response.body());

                shipOut.flush();
                System.out.println("Response sent successfully");

            } catch (Exception e) {
                System.err.println("Error processing request: " + e.getMessage());
                e.printStackTrace();

                // Send error response
                try {
                    String errorMsg = "Error: " + e.getMessage();
                    String errorResponse = "HTTP/1.1 502 Bad Gateway\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: " + errorMsg.length() + "\r\n" +
                            "Connection: keep-alive\r\n" +
                            "\r\n" +
                            errorMsg;
                    shipOut.write(errorResponse.getBytes());
                    shipOut.flush();
                    System.out.println("Error response sent");
                } catch (IOException ioe) {
                    System.err.println("Failed to send error response: " + ioe.getMessage());
                    break;
                }
            }
        }

        shipSocket.close();
        serverSocket.close();
        System.out.println("OffshoreProxy shut down");
    }

    private static class RequestData {
        String method;
        String url;
        List<String> headers;
        byte[] body;
    }

    private static RequestData readRequest(InputStream in) throws IOException {
        RequestData data = new RequestData();
        data.headers = new ArrayList<>();

        // Read request line
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }

        System.out.println("Request line: " + requestLine);
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) {
            throw new IOException("Invalid request line: " + requestLine);
        }

        data.method = parts[0];
        data.url = parts[1];

        // Read headers
        int contentLength = 0;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            data.headers.add(line);
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }

        // Read body if present
        if (contentLength > 0) {
            System.out.println("Reading request body: " + contentLength + " bytes");
            data.body = new byte[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read = in.read(data.body, totalRead, contentLength - totalRead);
                if (read == -1) {
                    throw new IOException("Unexpected end of stream while reading body");
                }
                totalRead += read;
            }
            System.out.println("Body read: " + totalRead + " bytes");
        } else {
            data.body = new byte[0];
        }

        return data;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        int prev = 0;

        while ((b = in.read()) != -1) {
            if (b == '\n' && prev == '\r') {
                // Remove the \r we just added
                if (line.length() > 0) {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            line.append((char) b);
            prev = b;
        }

        // EOF reached
        if (line.length() > 0) {
            return line.toString();
        }
        return null;
    }

    private static String getReasonPhrase(int statusCode) {
        switch (statusCode) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Unknown";
        }
    }
}