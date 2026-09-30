package io.kestra.plugin.pennylane;

import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.pennylane.models.Changelog;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Namespace-KV checkpoint for Pennylane changelog polls.
 * The stored value is {@code processedAt}, a newline, then comma-separated resource ids
 * that share that timestamp. The key length-prefixes each segment so adjacent ids cannot collide.
 */
public final class PennylaneWatermark {

    private PennylaneWatermark() {
    }

    public record State(String processedAt, List<Long> ids) {
    }

    public static String key(String flowId, String triggerId) {
        String flow = sanitize(flowId);
        String trigger = sanitize(triggerId);
        return "pl_wm_" + flow.length() + "_" + flow + "_" + trigger.length() + "_" + trigger;
    }

    public static String initialStart(ZonedDateTime date, Duration interval) {
        return date.minus(interval)
            .withZoneSameInstant(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static State load(RunContext runContext, String namespace, String key) throws Exception {
        var stored = runContext.namespaceKv(namespace).getValue(key);
        if (stored.isEmpty() || stored.get().value() == null) {
            return null;
        }
        return decode(stored.get().value());
    }

    public static void save(RunContext runContext, String namespace, String key, State state) throws Exception {
        runContext.namespaceKv(namespace).put(
            key,
            new KVValueAndMetadata(new KVMetadata("Pennylane changelog watermark", (Duration) null), encode(state)),
            true
        );
    }

    public static boolean alreadySeen(Changelog change, State previous) {
        if (previous == null || previous.processedAt() == null || previous.processedAt().isBlank()) {
            return false;
        }
        if (change == null || change.getProcessedAt() == null || change.getProcessedAt().isBlank()) {
            return false;
        }
        int cmp = compareTimestamps(change.getProcessedAt(), previous.processedAt());
        if (cmp < 0) {
            return true;
        }
        if (cmp > 0) {
            return false;
        }
        return change.getId() != null && previous.ids() != null && previous.ids().contains(change.getId());
    }

    public static State advance(List<Changelog> fetched, State previous, String startDate) {
        if (fetched == null || fetched.isEmpty()) {
            if (previous != null) {
                return previous;
            }
            return new State(startDate == null ? "" : startDate, List.of());
        }

        String max = null;
        for (Changelog change : fetched) {
            if (change.getProcessedAt() == null || change.getProcessedAt().isBlank()) {
                continue;
            }
            if (max == null || compareTimestamps(change.getProcessedAt(), max) > 0) {
                max = change.getProcessedAt();
            }
        }
        if (max == null) {
            if (previous != null) {
                return previous;
            }
            return new State(startDate == null ? "" : startDate, List.of());
        }

        Set<Long> ids = new LinkedHashSet<>();
        if (previous != null && previous.processedAt() != null
            && compareTimestamps(previous.processedAt(), max) == 0
            && previous.ids() != null) {
            ids.addAll(previous.ids());
        }
        for (Changelog change : fetched) {
            if (change.getId() != null && change.getProcessedAt() != null
                && compareTimestamps(change.getProcessedAt(), max) == 0) {
                ids.add(change.getId());
            }
        }
        return new State(max, List.copyOf(ids));
    }

    static String encode(State state) {
        String at = state.processedAt() == null ? "" : state.processedAt();
        StringBuilder ids = new StringBuilder();
        if (state.ids() != null) {
            for (Long id : state.ids()) {
                if (id == null) {
                    continue;
                }
                if (!ids.isEmpty()) {
                    ids.append(',');
                }
                ids.append(id);
            }
        }
        return at + "\n" + ids;
    }

    static State decode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Object at = map.get("processedAt");
            List<Long> ids = new ArrayList<>();
            Object rawIds = map.get("ids");
            if (rawIds instanceof List<?> list) {
                for (Object item : list) {
                    Long parsed = parseId(item);
                    if (parsed != null) {
                        ids.add(parsed);
                    }
                }
            }
            return new State(at == null ? "" : at.toString(), List.copyOf(ids));
        }
        String text = value instanceof String s ? s : String.valueOf(value);
        int newline = text.indexOf('\n');
        String at = newline < 0 ? text : text.substring(0, newline);
        String idPart = newline < 0 ? "" : text.substring(newline + 1);
        List<Long> ids = new ArrayList<>();
        if (!idPart.isBlank()) {
            for (String piece : idPart.split(",")) {
                Long parsed = parseId(piece.trim());
                if (parsed != null) {
                    ids.add(parsed);
                }
            }
        }
        return new State(at, List.copyOf(ids));
    }

    static int compareTimestamps(String left, String right) {
        try {
            return Instant.parse(left).compareTo(Instant.parse(right));
        } catch (DateTimeParseException ignored) {
            return left.compareTo(right);
        }
    }

    private static Long parseId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "x";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '.'
                || c == '_'
                || c == '-';
            sb.append(allowed ? c : '-');
        }
        return sb.toString();
    }
}
