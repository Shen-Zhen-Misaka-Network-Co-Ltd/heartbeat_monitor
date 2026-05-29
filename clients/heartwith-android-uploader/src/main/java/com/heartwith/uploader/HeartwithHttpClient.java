package com.heartwith.uploader;

public interface HeartwithHttpClient {
    Response post(String url, String contentType, byte[] body, String authorization) throws Exception;

    final class Response {
        public final int code;
        public final String body;

        public Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        public boolean isSuccessful() {
            return code >= 200 && code < 300;
        }
    }
}
