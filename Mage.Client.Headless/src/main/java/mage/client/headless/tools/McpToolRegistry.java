package mage.client.headless.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mage.client.headless.BridgeCallbackHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection-based MCP tool registry.
 * Scans tool classes for @Tool-annotated methods and auto-generates
 * input schemas from Java parameter types + @Param annotations.
 */
public class McpToolRegistry {

    private final List<ToolEntry> entries = new ArrayList<>();
    private final Map<String, ToolEntry> byName = new LinkedHashMap<>();

    public McpToolRegistry(Class<?>... toolClasses) {
        for (Class<?> cls : toolClasses) {
            ToolEntry entry = scan(cls);
            entries.add(entry);
            byName.put(entry.annotation.name(), entry);
        }
    }

    private static ToolEntry scan(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            Tool ann = m.getAnnotation(Tool.class);
            if (ann != null) {
                return new ToolEntry(ann, m);
            }
        }
        throw new RuntimeException("No @Tool method found in " + cls.getName());
    }

    /** Build the full tool definition list (for tools/list and JSON export). */
    public List<Map<String, Object>> getDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (ToolEntry entry : entries) {
            defs.add(buildDefinition(entry));
        }
        return defs;
    }

    /** Execute a tool by name, extracting args from the JsonObject. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(String name, JsonObject arguments, BridgeCallbackHandler handler) {
        ToolEntry entry = byName.get(name);
        if (entry == null) {
            throw new RuntimeException("Unknown tool: " + name);
        }
        Parameter[] params = entry.method.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();
            if (BridgeCallbackHandler.class.isAssignableFrom(type)) {
                args[i] = handler;
            } else {
                String paramName = params[i].getName();
                args[i] = extractArg(arguments, paramName, type);
            }
        }
        try {
            return (Map<String, Object>) entry.method.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke tool " + name, e);
        }
    }

    // -- Schema generation --

    private static Map<String, Object> buildDefinition(ToolEntry entry) {
        Map<String, Object> def = new HashMap<>();
        def.put("name", entry.annotation.name());
        def.put("description", entry.annotation.description());
        def.put("inputSchema", buildInputSchema(entry));
        def.put("outputSchema", buildOutputSchema(entry.annotation.output()));
        def.put("examples", buildExamples(entry.annotation.examples()));
        return def;
    }

    private static Map<String, Object> buildInputSchema(ToolEntry entry) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter p : entry.method.getParameters()) {
            if (BridgeCallbackHandler.class.isAssignableFrom(p.getType())) continue;
            Param param = p.getAnnotation(Param.class);
            if (param == null) continue;

            String name = p.getName();
            Map<String, Object> prop = new HashMap<>();
            addJsonType(prop, p.getType());
            prop.put("description", param.description());
            properties.put(name, prop);

            if (param.required()) {
                required.add(name);
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static void addJsonType(Map<String, Object> prop, Class<?> type) {
        if (type == String.class) {
            prop.put("type", "string");
        } else if (type == Integer.class || type == int.class) {
            prop.put("type", "integer");
        } else if (type == Long.class || type == long.class) {
            prop.put("type", "integer");
        } else if (type == Boolean.class || type == boolean.class) {
            prop.put("type", "boolean");
        } else if (type == String[].class) {
            prop.put("type", "array");
            Map<String, Object> items = new HashMap<>();
            items.put("type", "string");
            prop.put("items", items);
        } else if (type == int[].class) {
            prop.put("type", "array");
            Map<String, Object> items = new HashMap<>();
            items.put("type", "integer");
            prop.put("items", items);
        } else {
            throw new RuntimeException("Unsupported parameter type: " + type.getName());
        }
    }

    private static Map<String, Object> buildOutputSchema(Tool.Field[] fields) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        for (Tool.Field f : fields) {
            Map<String, Object> prop = new HashMap<>();
            String type = f.type();
            if (type.startsWith("array[") && type.endsWith("]")) {
                prop.put("type", "array");
                Map<String, Object> items = new HashMap<>();
                items.put("type", type.substring(6, type.length() - 1));
                prop.put("items", items);
            } else {
                prop.put("type", type);
            }
            prop.put("description", f.description());
            properties.put(f.name(), prop);
        }
        schema.put("properties", properties);
        return schema;
    }

    private static List<Map<String, Object>> buildExamples(Tool.Example[] examples) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool.Example ex : examples) {
            Map<String, Object> m = new HashMap<>();
            m.put("label", ex.label());
            m.put("value", ex.value());
            list.add(m);
        }
        return list;
    }

    // -- Arg extraction --

    private static Object extractArg(JsonObject obj, String key, Class<?> type) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;

        if (type == String.class) {
            return obj.get(key).getAsString();
        } else if (type == Integer.class) {
            return obj.get(key).getAsInt();
        } else if (type == Long.class) {
            return obj.get(key).getAsLong();
        } else if (type == Boolean.class) {
            return obj.get(key).getAsBoolean();
        } else if (type == String[].class) {
            JsonArray arr = obj.getAsJsonArray(key);
            String[] result = new String[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                JsonElement elem = arr.get(i);
                result[i] = elem.isJsonNull() ? null : elem.getAsString();
            }
            return result;
        } else if (type == int[].class) {
            JsonArray arr = obj.getAsJsonArray(key);
            int[] result = new int[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                result[i] = arr.get(i).isJsonNull() ? 0 : arr.get(i).getAsInt();
            }
            return result;
        }
        throw new RuntimeException("Unsupported parameter type: " + type.getName());
    }

    private static class ToolEntry {
        final Tool annotation;
        final Method method;

        ToolEntry(Tool annotation, Method method) {
            this.annotation = annotation;
            this.method = method;
        }
    }
}
