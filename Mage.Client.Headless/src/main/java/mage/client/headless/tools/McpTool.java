package mage.client.headless.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

/**
 * Interface for MCP tool implementations.
 * Each tool provides its own schema (name, description, input/output schemas, examples)
 * and execution logic.
 */
public interface McpTool {
    String name();
    String description();
    Map<String, Object> outputSchema();
    List<Map<String, Object>> examples();
    Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler);

    /** Assembles the full tool definition from the above methods. */
    default Map<String, Object> definition() {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name());
        tool.put("description", description());
        tool.put("inputSchema", inputSchema());
        tool.put("outputSchema", outputSchema());
        tool.put("examples", examples());
        return tool;
    }

    /** Default: no input params. Override in tools that have params. */
    default Map<String, Object> inputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("additionalProperties", false);
        return schema;
    }

    // -- Static helpers for building output schemas --

    static Map<String, Object> field(String name, String type, String desc) {
        Map<String, Object> f = new HashMap<>();
        f.put("name", name);
        f.put("type", type);
        f.put("description", desc);
        return f;
    }

    static Map<String, Object> field(String name, String type, String desc, String conditional) {
        Map<String, Object> f = field(name, type, desc);
        f.put("conditional", conditional);
        return f;
    }

    @SafeVarargs
    static Map<String, Object> outputSchema(Map<String, Object>... fields) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        for (Map<String, Object> fld : fields) {
            String name = (String) fld.get("name");
            String type = (String) fld.get("type");
            Map<String, Object> prop = new HashMap<>();
            // Convert "array[X]" to proper JSON Schema array type
            if (type.startsWith("array[") && type.endsWith("]")) {
                prop.put("type", "array");
                Map<String, Object> items = new HashMap<>();
                items.put("type", type.substring(6, type.length() - 1));
                prop.put("items", items);
            } else {
                prop.put("type", type);
            }
            if (fld.containsKey("description")) {
                prop.put("description", fld.get("description"));
            }
            properties.put(name, prop);
        }
        schema.put("properties", properties);
        return schema;
    }

    static Map<String, Object> example(String label, String value) {
        Map<String, Object> ex = new HashMap<>();
        ex.put("label", label);
        ex.put("value", value);
        return ex;
    }

    @SafeVarargs
    static <T> List<T> listOf(T... items) {
        List<T> list = new ArrayList<>();
        for (T item : items) {
            list.add(item);
        }
        return list;
    }

    // -- Static helpers for building input schemas --

    static Map<String, Object> param(String name, String type, String description) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    static Map<String, Object> arrayParam(String name, String itemType, String description) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("type", "array");
        p.put("itemType", itemType);
        p.put("description", description);
        return p;
    }

    @SafeVarargs
    static Map<String, Object> inputSchema(List<String> required, Map<String, Object>... params) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        for (Map<String, Object> p : params) {
            String name = (String) p.get("name");
            Map<String, Object> prop = new HashMap<>();
            String type = (String) p.get("type");
            if ("array".equals(type)) {
                prop.put("type", "array");
                Map<String, Object> items = new HashMap<>();
                items.put("type", p.get("itemType"));
                prop.put("items", items);
            } else {
                prop.put("type", type);
            }
            prop.put("description", p.get("description"));
            properties.put(name, prop);
        }
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    // Convenience overload: no required params
    @SafeVarargs
    static Map<String, Object> inputSchema(Map<String, Object>... params) {
        return inputSchema(null, params);
    }

    // -- Static helpers for extracting arguments --

    static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    static int getInt(JsonObject obj, String key, int defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
        return obj.get(key).getAsInt();
    }

    static Integer getIntOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsInt();
    }

    static Long getLongOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsLong();
    }

    static Boolean getBooleanOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsBoolean();
    }
}
