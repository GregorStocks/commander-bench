package mage.client.headless.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method as an MCP tool.
 * The method's parameters (excluding BridgeCallbackHandler) become the tool's input schema,
 * derived automatically from Java types and @Param annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    String name();
    String description();
    Field[] output();
    Example[] examples();

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Field {
        String name();
        String type();
        String description();
        String conditional() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Example {
        String label();
        String value();
    }
}
