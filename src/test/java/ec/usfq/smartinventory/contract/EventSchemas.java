package ec.usfq.smartinventory.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Valida sobres de eventos contra contracts/schemas/&lt;eventType&gt;.v&lt;schemaVersion&gt;.json. */
public final class EventSchemas {

    public static final Path DIR = Path.of("contracts", "schemas");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

    private EventSchemas() {
    }

    public static Path file(String eventType, int version) {
        return DIR.resolve(eventType + ".v" + version + ".json");
    }

    public static JsonNode schemaJson(String eventType, int version) {
        try {
            return MAPPER.readTree(Files.readString(file(eventType, version)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonSchema schema(String eventType, int version) {
        return CACHE.computeIfAbsent(eventType + "@" + version, k -> FACTORY.getSchema(schemaJson(eventType, version)));
    }

    /** @return errores de validacion (vacio = conforme). El tipo y la version se leen del propio sobre. */
    public static Set<String> validate(String envelopeJson) {
        try {
            JsonNode node = MAPPER.readTree(envelopeJson);
            String type = node.path("eventType").asText();
            int version = node.path("schemaVersion").asInt(1);
            if (!Files.exists(file(type, version))) {
                return Set.of("no schema for " + type + " v" + version);
            }
            return schema(type, version).validate(node).stream()
                    .map(ValidationMessage::getMessage).collect(Collectors.toSet());
        } catch (IOException e) {
            return Set.of("invalid JSON: " + e.getMessage());
        }
    }
}
