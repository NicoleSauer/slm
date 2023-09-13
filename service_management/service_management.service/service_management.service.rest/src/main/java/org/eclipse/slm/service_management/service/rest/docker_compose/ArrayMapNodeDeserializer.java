package org.eclipse.slm.service_management.service.rest.docker_compose;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ArrayMapNodeDeserializer<T extends Map<String, Object>> extends StdDeserializer<T> {

    public ArrayMapNodeDeserializer() {
        this(null);
    }

    public ArrayMapNodeDeserializer(Class<?> vc) {
        super(vc);
    }

    @SneakyThrows
    @Override
    public T deserialize(JsonParser jp, DeserializationContext ctxt)
            throws IOException {
        JsonNode jsonNode = jp.getCodec().readTree(jp);

        var map = (T)this._valueClass.getDeclaredConstructor().newInstance();

        if (jsonNode.isArray()) {
            var arrayNode = (ArrayNode) jsonNode;
            for (var element : arrayNode) {
                if (element.textValue().contains("=")) {
                    try {
                        var envVarKey = element.textValue().split("=", 2)[0];
                        String envVarValue = "";
                        if (element.textValue().split("=").length > 1) {
                            envVarValue = element.textValue().split("=", 2)[1];
                        }
                        map.put(envVarKey, envVarValue);
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                        int i = 0;
                    }

                }
                else {
                    map.put(element.textValue(), null);
                }
            }

        } else if (jsonNode.isObject()) {
            var objectNode = (ObjectNode) jsonNode;
            var iter = objectNode.fields();
            while (iter.hasNext()) {
                Map.Entry<String, JsonNode> entry = iter.next();
                if (entry.getValue() instanceof ObjectNode) {
                    var mapper = new ObjectMapper();
                    var valueAsMap = mapper.convertValue(entry.getValue(), Map.class);
                    map.put(entry.getKey(), valueAsMap);
                }
                else {
                    map.put(entry.getKey(), entry.getValue().asText());
                }
            }
        }

        return map;
    }
}
