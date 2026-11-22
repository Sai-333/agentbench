package com.agentbench.platform.gpu;

import com.agentbench.agents.infra.SecretRedactor;

import java.util.regex.Pattern;

/**
 * Device and detail strings stored on a probe row. Secrets are redacted and
 * control characters are stripped. Software renderers are not treated as GPUs.
 */
final class GpuText {

    private static final Pattern SOFTWARE = Pattern.compile(
            "llvmpipe|softpipe|swrast|lavapipe|swiftshader|basic render|virgl",
            Pattern.CASE_INSENSITIVE);

    private GpuText() {
    }

    static String device(String raw) {
        String cleaned = clean(raw, 200);
        return cleaned;
    }

    static String detail(String raw) {
        return clean(raw, 300);
    }

    static boolean software(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return SOFTWARE.matcher(name).find();
    }

    private static String clean(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String redacted = SecretRedactor.redact(raw);
        if (redacted == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(redacted.length(), max));
        boolean spaced = false;
        for (int i = 0; i < redacted.length() && out.length() < max; i++) {
            char c = redacted.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                c = ' ';
            }
            if (c == ' ') {
                if (spaced || out.isEmpty()) {
                    continue;
                }
                spaced = true;
            } else {
                spaced = false;
            }
            out.append(c);
        }
        String value = out.toString().strip();
        return value.isEmpty() ? null : value;
    }

    static String vendorLabel(int vendor) {
        return switch (vendor) {
            case 0x10de -> "NVIDIA";
            case 0x1002 -> "AMD";
            case 0x8086 -> "Intel";
            default -> "GPU";
        };
    }
}
