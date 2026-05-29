package com.heartwith.uploader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class UrlConnectionHeartwithHttpClient implements HeartwithHttpClient {
    private final HeartwithCleartextScope cleartextScope;
    private final boolean rawHttpFallbackEnabled;

    public UrlConnectionHeartwithHttpClient() {
        this(HeartwithCleartextScope.NONE, false);
    }

    public UrlConnectionHeartwithHttpClient(
            HeartwithCleartextScope cleartextScope,
            boolean rawHttpFallbackEnabled
    ) {
        this.cleartextScope = cleartextScope == null ? HeartwithCleartextScope.NONE : cleartextScope;
        this.rawHttpFallbackEnabled = rawHttpFallbackEnabled;
    }

    @Override
    public Response post(String url, String contentType, byte[] body, String authorization) throws Exception {
        try {
            return urlConnectionPost(url, contentType, body, authorization);
        } catch (Exception throwable) {
            if (!rawHttpFallbackEnabled || !shouldFallbackToRawHttp(url, throwable)) {
                throw throwable;
            }
            return rawHttpPost(url, contentType, body, authorization);
        }
    }

    private Response urlConnectionPost(
            String url,
            String contentType,
            byte[] body,
            String authorization
    ) throws Exception {
        boolean cleartext = url.startsWith("http://");
        if (cleartext) {
            cleartextScope.enter();
        }
        try {
            HttpURLConnection connection = open(url, contentType);
            return execute(connection, body, authorization);
        } finally {
            if (cleartext) {
                cleartextScope.exit();
            }
        }
    }

    private HttpURLConnection open(String url, String contentType) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(2_500);
        connection.setReadTimeout(5_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Connection", "keep-alive");
        connection.setDoOutput(true);
        return connection;
    }

    private Response execute(HttpURLConnection connection, byte[] body, String authorization) throws Exception {
        boolean completed = false;
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        try {
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream output = connection.getOutputStream();
            output.write(body);
            output.close();
            int code = connection.getResponseCode();
            String responseBody = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            completed = true;
            return new Response(code, responseBody);
        } finally {
            if (!completed) {
                connection.disconnect();
            }
        }
    }

    private boolean shouldFallbackToRawHttp(String url, Throwable throwable) {
        if (!url.startsWith("http://")) {
            return false;
        }
        String message = throwable.getMessage();
        if (message == null) {
            message = "";
        }
        String lower = message.toLowerCase();
        return throwable instanceof SecurityException
                || lower.contains("cleartext")
                || lower.contains("not permitted")
                || lower.contains("unknown service");
    }

    private Response rawHttpPost(
            String urlValue,
            String contentType,
            byte[] body,
            String authorization
    ) throws Exception {
        URL url = new URL(urlValue);
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : 80;
        String path = url.getFile() == null || url.getFile().isEmpty() ? "/" : url.getFile();
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 2_500);
        socket.setSoTimeout(5_000);
        try {
            OutputStream output = socket.getOutputStream();
            StringBuilder headers = new StringBuilder();
            headers.append("POST ").append(path).append(" HTTP/1.1\r\n");
            headers.append("Host: ").append(host);
            if (url.getPort() > 0) {
                headers.append(':').append(port);
            }
            headers.append("\r\nConnection: close\r\n");
            headers.append("Content-Type: ").append(contentType).append("\r\n");
            headers.append("Content-Length: ").append(body.length).append("\r\n");
            if (authorization != null) {
                headers.append("Authorization: ").append(authorization).append("\r\n");
            }
            headers.append("\r\n");
            output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();

            byte[] responseBytes = readAllBytes(socket.getInputStream());
            String responseText = new String(responseBytes, StandardCharsets.UTF_8);
            int statusEnd = responseText.indexOf("\r\n");
            if (statusEnd < 0 || !responseText.startsWith("HTTP/")) {
                throw new IllegalStateException("bad http response");
            }
            String statusLine = responseText.substring(0, statusEnd);
            String[] parts = statusLine.split(" ");
            int code = parts.length >= 2 ? Integer.parseInt(parts[1]) : 0;
            int bodyStart = responseText.indexOf("\r\n\r\n");
            String responseBody = bodyStart >= 0 ? responseText.substring(bodyStart + 4) : "";
            return new Response(code, responseBody);
        } finally {
            socket.close();
        }
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        return new String(readAllBytes(input), StandardCharsets.UTF_8);
    }

    private byte[] readAllBytes(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        input.close();
        return out.toByteArray();
    }
}
