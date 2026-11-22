package com.agentbench.platform.gpu;

import com.agentbench.execution.service.HostCommandExecutor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Names physical GPUs from {@code nvidia-smi} and Linux DRM sysfs.
 * Virtual display adapters are ignored. This class does not produce a score.
 */
final class GpuDeviceDiscovery {

    static final Set<Integer> VIRTUAL_VENDORS = Set.of(0x1234, 0x1af4, 0x15ad, 0x80ee, 0x1b36);
    static final Set<String> VIRTUAL_DRIVERS = Set.of(
            "virtio-gpu",
            "bochs-drm",
            "vkms",
            "vmwgfx",
            "hyperv_drm",
            "simpledrm",
            "simple-framebuffer",
            "efifb",
            "vboxvideo",
            "qxl");

    private static final Pattern CARD = Pattern.compile("card\\d+");

    private final HostCommandExecutor commands;
    private final Path sysfsDrm;

    GpuDeviceDiscovery(HostCommandExecutor commands, Path sysfsDrm) {
        this.commands = commands;
        this.sysfsDrm = sysfsDrm;
    }

    record Device(String name, boolean nvidiaFamily) {
    }

    List<Device> discover() {
        List<Device> fromSmi = nvidiaSmi();
        List<Device> fromSysfs = sysfs();
        List<Device> out = new ArrayList<>(fromSmi);
        for (Device device : fromSysfs) {
            if (fromSmi.isEmpty() || !device.nvidiaFamily()) {
                out.add(device);
            }
            if (out.size() >= 8) {
                break;
            }
        }
        if (out.size() > 8) {
            return List.copyOf(out.subList(0, 8));
        }
        return List.copyOf(out);
    }

    private List<Device> nvidiaSmi() {
        if (commands == null) {
            return List.of();
        }
        HostCommandExecutor.Result result;
        try {
            result = commands.run(
                    List.of("nvidia-smi", "--query-gpu=name", "--format=csv,noheader"),
                    Duration.ofSeconds(3));
        } catch (RuntimeException ex) {
            return List.of();
        }
        if (!result.started() || result.timedOut() || result.exitCode() != 0) {
            return List.of();
        }
        List<Device> devices = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            String name = GpuText.device(stripCsv(line));
            if (name == null || GpuText.software(name)) {
                continue;
            }
            devices.add(new Device(name, true));
            if (devices.size() >= 8) {
                break;
            }
        }
        return devices;
    }

    private List<Device> sysfs() {
        if (sysfsDrm == null || !Files.isDirectory(sysfsDrm)) {
            return List.of();
        }
        List<Device> devices = new ArrayList<>();
        try (DirectoryStream<Path> cards = Files.newDirectoryStream(sysfsDrm)) {
            for (Path card : cards) {
                String filename = card.getFileName().toString();
                if (!CARD.matcher(filename).matches()) {
                    continue;
                }
                Device device = readCard(card);
                if (device != null) {
                    devices.add(device);
                }
            }
        } catch (IOException ex) {
            return List.of();
        }
        return devices;
    }

    private static Device readCard(Path card) {
        Path device = card.resolve("device");
        Integer vendor = parseHex(readSmall(device.resolve("vendor")));
        Integer deviceId = parseHex(readSmall(device.resolve("device")));
        if (vendor == null || deviceId == null) {
            return null;
        }
        String driver = driverName(device);
        if (VIRTUAL_VENDORS.contains(vendor) || VIRTUAL_DRIVERS.contains(driver)) {
            return null;
        }
        String name = GpuText.device(GpuText.vendorLabel(vendor) + " " + Integer.toHexString(deviceId));
        if (name == null || GpuText.software(name)) {
            return null;
        }
        boolean nvidia = vendor == 0x10de || "nvidia".equals(driver) || "nouveau".equals(driver);
        return new Device(name, nvidia);
    }

    private static String driverName(Path deviceDir) {
        String uevent = readSmall(deviceDir.resolve("uevent"));
        if (uevent != null) {
            for (String line : uevent.split("\\R")) {
                if (line.startsWith("DRIVER=")) {
                    return line.substring("DRIVER=".length()).trim().toLowerCase(Locale.ROOT);
                }
            }
        }
        Path driver = deviceDir.resolve("driver");
        try {
            if (Files.isSymbolicLink(driver)) {
                Path target = Files.readSymbolicLink(driver);
                return target.getFileName().toString().toLowerCase(Locale.ROOT);
            }
        } catch (IOException ignored) {
            return "";
        }
        return "";
    }

    private static String readSmall(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            String text = Files.readString(path);
            if (text.length() > 4000) {
                text = text.substring(0, 4000);
            }
            return text;
        } catch (IOException ex) {
            return null;
        }
    }

    private static Integer parseHex(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.startsWith("0x")) {
                return Integer.parseUnsignedInt(text.substring(2), 16);
            }
            return Integer.parseUnsignedInt(text, 16);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String stripCsv(String line) {
        if (line == null) {
            return null;
        }
        String value = line.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
