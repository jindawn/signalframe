package com.signalframe.analysis.application.steps;

import com.signalframe.shared.JsonCodec;
import java.time.Instant;
import java.util.*;

/**
 * 由 reader 真正反序列化的 DTO 直接派生回复 schema。
 *
 * <p>Stage 1 与 Stage 2 都曾把生成的 contract 投影当作"回复必须匹配的 JSON Schema"，
 * 而 reader 反序列化的是更窄的 {@code ProtocolModel} DTO。两套字段体系不同，于是模型
 * 照 schema 输出、reader 拒绝，每次真实请求只暴露下一个字段。
 *
 * <p>这里以 DTO 为唯一来源：schema 的每个属性都来自 record 的规范构造器，因此二者
 * <b>不可能</b>漂移，也不需要手工维护第二套定义。严格性不降：每个对象都设
 * {@code additionalProperties:false}，未知字段仍被 Jackson 拒绝。
 */
public final class StageReplySchema {

  private StageReplySchema() {}

  /** 为根 record 生成完整 schema，嵌套 record 收进 {@code $defs}。 */
  public static String render(JsonCodec json, Class<?> root) {
    var defs = new LinkedHashMap<String, Object>();
    var document = new LinkedHashMap<String, Object>(node(root, defs));
    document.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    if (!defs.isEmpty()) document.put("$defs", defs);
    return json.write(document);
  }

  private static Map<String, Object> node(Class<?> type, Map<String, Object> defs) {
    if (type == String.class) return Map.of("type", "string");
    if (type == UUID.class) return Map.of("type", "string", "format", "uuid");
    if (type == Integer.class || type == int.class) return Map.of("type", "integer");
    if (type == Long.class || type == long.class) return Map.of("type", "integer");
    if (type == Boolean.class || type == boolean.class) return Map.of("type", "boolean");
    if (type == Instant.class) return Map.of("type", "string", "format", "date-time");
    if (type.isEnum()) {
      var values = new ArrayList<String>();
      for (var constant : type.getEnumConstants()) values.add(((Enum<?>) constant).name());
      return Map.of("type", "string", "enum", List.copyOf(values));
    }
    if (List.class.isAssignableFrom(type)) return Map.of(
      "type",
      "array",
      "items",
      Map.of("type", "object")
    );
    if (type.isRecord()) return object(type, defs);
    // 未知类型不猜：报告为可自由取值，而不是编一个形状
    return Map.of();
  }

  private static Map<String, Object> object(Class<?> record, Map<String, Object> defs) {
    var properties = new LinkedHashMap<String, Object>();
    var required = new ArrayList<String>();
    for (var component : record.getRecordComponents()) {
      properties.put(component.getName(), property(component, defs));
      required.add(component.getName());
    }
    var node = new LinkedHashMap<String, Object>();
    node.put("type", "object");
    node.put("additionalProperties", false);
    node.put("properties", properties);
    node.put("required", List.copyOf(required));
    defs.putIfAbsent(record.getSimpleName(), node);
    return node;
  }

  /**
   * 组件声明决定属性形状。
   *
   * <p>{@code List<X>} 的 X 只存在于泛型实参里，这是 items 形状的唯一可靠来源；
   * 拿不到实参时不猜，退化为可自由取值的数组而不是编一个形状。
   */
  private static Map<String, Object> property(
    java.lang.reflect.RecordComponent component,
    Map<String, Object> defs
  ) {
    var generic = component.getGenericType();
    if (
      generic instanceof java.lang.reflect.ParameterizedType parameterized &&
      List.class.isAssignableFrom((Class<?>) parameterized.getRawType())
    ) {
      return Map.of(
        "type",
        "array",
        "items",
        reference(elementType(parameterized), defs)
      );
    }
    return reference(component.getType(), defs);
  }

  private static Class<?> elementType(java.lang.reflect.ParameterizedType listType) {
    var argument = listType.getActualTypeArguments()[0];
    if (argument instanceof Class<?> direct) return direct;
    if (argument instanceof java.lang.reflect.ParameterizedType nested) {
      return (Class<?>) nested.getRawType();
    }
    return Object.class;
  }

  /** 复杂类型走 {@code $defs} 引用，标量直接内联。 */
  private static Map<String, Object> reference(Class<?> type, Map<String, Object> defs) {
    if (type.isRecord()) {
      object(type, defs);
      return Map.of("$ref", "#/$defs/" + type.getSimpleName());
    }
    return node(type, defs);
  }
}
