package com.signalframe.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The span-resolution rule: the quote is the evidence and the offsets are bookkeeping,
 * so a verbatim quote gets its position derived, while a quote that does not occur is
 * left alone for the protocol check to reject.
 */
class SourceSpanResolverTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String SOURCE =
    "第一段：某公司宣布降价。\n第二段：供应商尚未回应。\n第三段：某公司宣布降价，但范围有限。";

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> refs(String resolved) {
    var root = (Map<String, Object>) JSON.readValue(resolved, Object.class);
    var facts = (List<Map<String, Object>>) root.get("facts");
    return (List<Map<String, Object>>) facts.getFirst().get("sourceRefs");
  }

  private static String reply(int start, int end, String quote) {
    return (
      "{\"facts\":[{\"statement\":\"s\",\"sourceRefs\":[{\"sourceId\":\"x\"," +
      "\"quote\":" +
      JSON.writeValueAsString(quote) +
      ",\"startOffset\":" +
      start +
      ",\"endOffset\":" +
      end +
      "}]}]}"
    );
  }

  @Test
  void aVerbatimQuoteGetsItsOffsetsDerived() {
    var quote = "供应商尚未回应";
    var at = SOURCE.indexOf(quote);
    var resolved = SourceSpanResolver.resolve(reply(at + 1, at + 1 + 99, quote), SOURCE);
    var ref = refs(resolved).getFirst();
    assertEquals(at, ref.get("startOffset"));
    assertEquals(at + quote.length(), ref.get("endOffset"));
    assertEquals(
      quote,
      SOURCE.substring(
        (int) ref.get("startOffset"),
        (int) ref.get("endOffset")
      )
    );
  }

  @Test
  void aRepeatedQuoteUsesTheOccurrenceClosestToTheClaim() {
    var quote = "某公司宣布降价";
    var first = SOURCE.indexOf(quote);
    var second = SOURCE.lastIndexOf(quote);
    assertNotEquals(first, second, "the fixture must repeat the quote");
    var resolved = SourceSpanResolver.resolve(
      reply(second + 1, second + 1 + quote.length(), quote),
      SOURCE
    );
    assertEquals(second, refs(resolved).getFirst().get("startOffset"));
  }

  @Test
  void aQuoteThatIsNotVerbatimIsLeftForTheProtocolCheck() {
    var resolved = SourceSpanResolver.resolve(reply(5, 9, "原文里没有这句话"), SOURCE);
    var ref = refs(resolved).getFirst();
    assertEquals(5, ref.get("startOffset"));
    assertEquals(9, ref.get("endOffset"));
  }

  @Test
  void spansAreFoundWhereverTheyAreNested() {
    var quote = "但范围有限";
    var at = SOURCE.indexOf(quote);
    var raw =
      "{\"summary\":\"s\",\"unknowns\":[{\"statement\":\"u\",\"sourceRefs\":[{\"quote\":" +
      JSON.writeValueAsString(quote) +
      ",\"startOffset\":0,\"endOffset\":1}]}]}";
    var resolved = (Map<String, Object>) JSON.readValue(
      SourceSpanResolver.resolve(raw, SOURCE),
      Object.class
    );
    var unknown = (Map<String, Object>) ((List<?>) resolved.get("unknowns")).getFirst();
    var ref = (Map<String, Object>) ((List<?>) unknown.get("sourceRefs")).getFirst();
    assertEquals(at, ref.get("startOffset"));
  }

  @Test
  void aReplyThatIsNotJsonIsReturnedUnchanged() {
    assertEquals("not json at all", SourceSpanResolver.resolve("not json at all", SOURCE));
    assertEquals("", SourceSpanResolver.resolve("", SOURCE));
    assertEquals(null, SourceSpanResolver.resolve(null, SOURCE));
  }

  @Test
  void aReplyWithoutSpansIsReturnedUnchanged() {
    var raw = "{\"summary\":\"no spans here\"}";
    assertEquals(raw, SourceSpanResolver.resolve(raw, SOURCE));
  }
}
