package com.heartwith.uploader;

public final class HeartwithUploadConfig {
    public final boolean enabled;
    public final String serverUrl;
    public final String displayName;
    public final String deviceModel;
    public final String clientPlatform;
    public final String appVersion;

    public HeartwithUploadConfig(
            boolean enabled,
            String serverUrl,
            String displayName,
            String deviceModel,
            String clientPlatform,
            String appVersion
    ) {
        this.enabled = enabled;
        this.serverUrl = trimTrailingSlash(nonEmpty(serverUrl, "http://127.0.0.1:8000"));
        this.displayName = nonEmpty(displayName, "Android");
        this.deviceModel = nonEmpty(deviceModel, "Android");
        this.clientPlatform = nonEmpty(clientPlatform, "android");
        this.appVersion = nonEmpty(appVersion, "0.0.0");
    }

    private static String nonEmpty(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
