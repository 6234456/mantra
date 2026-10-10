import com.xqiou.mantra.core.FileSources;
import com.xqiou.mantra.core.Mantra;
import com.xqiou.mantra.core.SourceLocation;
import com.xqiou.mantra.core.api.CalculationOptions;
import com.xqiou.mantra.core.model.Value;
import com.xqiou.mantra.core.model.ValueType;
import com.xqiou.mantra.core.read.SourceResolver;
import com.xqiou.mantra.core.read.SourceText;
import com.xqiou.mantra.core.view.NodeKind;
import com.xqiou.mantra.excel.ExcelExport;
import com.xqiou.mantra.excel.ExcelOptions;
import com.xqiou.mantra.render.Render;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Audit-only launcher: uses public APIs and never changes exporter semantics or workbook cells. */
class ExcelTemplateAuditExport {
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 4) throw new IllegalArgumentException("schema case layout-or-default output-directory");
    var schemaPath = Path.of(arguments[0]).toAbsolutePath().normalize();
    var casePath = Path.of(arguments[1]).toAbsolutePath().normalize();
    var output = Path.of(arguments[3]);
    Files.createDirectories(output);
    var sources = new LinkedHashMap<String, Object>();
    var root = FileSources.INSTANCE.read(schemaPath);
    capture(sources, root);
    SourceResolver resolver = (path, relative) -> {
      var source = FileSources.INSTANCE.resolve(path, relative);
      if (source != null) capture(sources, source);
      return source;
    };
    var schema = Mantra.INSTANCE.loadSchema(root, resolver);
    capture(sources, FileSources.INSTANCE.read(casePath));
    var facts = Mantra.INSTANCE.loadCase(casePath);
    var result = Mantra.INSTANCE.calculate(schema, facts, List.of(), new CalculationOptions());
    if (!result.getSucceeded()) throw new IllegalStateException("Mantra calculation failed: " + result.getDiagnostics());
    var layout = arguments[2].equals("default") ? Render.INSTANCE.defaultLayout(result)
        : Render.INSTANCE.loadLayout(Path.of(arguments[2]));
    if (!arguments[2].equals("default")) capture(sources, FileSources.INSTANCE.read(Path.of(arguments[2])));
    try (var export = ExcelExport.INSTANCE.workbook(result, layout, new ExcelOptions())) {
      var workbook = output.resolve("artifact.xlsx");
      export.write(workbook);
      var mappings = new ArrayList<Object>();
      for (var node : result.getView().getNodes().values()) {
        if (node.getType() == ValueType.TABLE && node.getInput() != null) {
          var input = result.getCase().getInputs().get(node.getId());
          var rows = input instanceof Value.Vec vector ? vector.getItems() : List.<Value>of();
          for (int row = 0; row < rows.size(); row++) {
            for (var column : node.getInput().getColumns()) {
              var fields = rows.get(row) instanceof Value.MapV map ? map.getEntries() : Map.<Value, Value>of();
              var value = fields.getOrDefault(new Value.Kw(column.getName()), Value.Nil.INSTANCE);
              mappings.add(map("kind", "tableInput", "node", node.getId(), "coord", List.of(),
                  "rowIndex", row, "column", column.getName(), "type", column.getType().getKeyword(),
                  "optional", column.getOptional(), "editableInput", true,
                  "address", export.tableAddress(node.getId(), row, column.getName()), "value", value(value),
                  "source", location(node.getLocation())));
            }
          }
          continue;
        }
        for (var coord : result.getView().coordinates(node.getId(), Map.of())) {
          mappings.add(map("kind", "node", "node", node.getId(), "coord", coord,
              "type", node.getType().getKeyword(), "editableInput", node.getKind() == NodeKind.INPUT,
              "address", export.address(node.getId(), coord), "value", value(node.value(coord)),
              "source", location(node.getLocation())));
        }
        var aggregate = export.aggregateAddress(node.getId(), Map.of());
        if (aggregate != null) mappings.add(map("kind", "aggregate", "node", node.getId(), "coord", List.of(),
            "type", node.getType().getKeyword(), "editableInput", false, "address", aggregate,
            "value", value(result.getView().reduce(node.getId(), Map.of()).getValue()),
            "source", location(node.getLocation())));
      }
      var report = export.getReport();
      var fallbacks = report.getFallbacks().stream().map(fallback -> map("sheet", fallback.getSheet(),
          "cell", fallback.getCell(), "node", fallback.getNodeId(), "reason", fallback.getReason())).toList();
      var metadata = map("format", "mantra.excel-template-audit-provenance/1",
          "schema", map("id", result.getView().getSchema().getId(), "version", result.getView().getSchema().getVersion()),
          "case", result.getCase().getId(), "succeeded", result.getSucceeded(),
          "validationPassed", result.getValidationPassed(), "sourceDocuments", sources,
          "xlsxSHA256", hash(Files.readAllBytes(workbook)), "mappings", mappings,
          "diagnostics", result.getDiagnostics().stream().map(diagnostic -> map("code", diagnostic.getCode(),
              "category", diagnostic.getCategory().name(), "message", diagnostic.getMessage())).toList(),
          "exportReport", map("sheets", report.getSheets(), "formulaCells", report.getFormulaCells(),
              "inputCells", report.getInputCells(), "names", report.getNames(), "fallbacks", fallbacks,
              "evaluationErrors", report.getEvaluationErrors()));
      Files.writeString(output.resolve("provenance.json"), json(metadata) + "\n");
    }
  }

  static void capture(Map<String, Object> sources, SourceText source) {
    var path = Path.of(source.getBase()).resolve(source.getName()).normalize().toString();
    sources.put(path, map("sha256", hash(source.getText().getBytes(StandardCharsets.UTF_8)), "text", source.getText()));
  }

  static Map<String, Object> location(SourceLocation location) {
    return location == null ? null : map("document", location.getSource(), "line", location.getLine(),
        "column", location.getColumn(), "startOffset", location.getStartOffset(), "endOffset", location.getEndOffset());
  }

  static Map<String, Object> value(Value value) {
    if (value instanceof Value.Num number) return map("type", "decimal", "text", number.getValue().toPlainString());
    if (value instanceof Value.Bool bool) return map("type", "boolean", "value", bool.getValue());
    if (value instanceof Value.Text text) return map("type", "text", "text", text.getValue());
    if (value instanceof Value.Kw keyword) return map("type", "keyword", "text", keyword.getName());
    if (value instanceof Value.Date date) return map("type", "date", "text", date.getValue().toString());
    return map("type", value == Value.Nil.INSTANCE ? "nil" : "structured", "text", value.toString());
  }

  static String hash(byte[] bytes) {
    try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  static Map<String, Object> map(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
    return result;
  }

  static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof Boolean || value instanceof Number) return value.toString();
    if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream()
        .map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).toList()) + "}";
    if (value instanceof Iterable<?> values) {
      var items = new ArrayList<String>();
      for (var item : values) items.add(json(item));
      return "[" + String.join(",", items) + "]";
    }
    var text = value.toString();
    var escaped = new StringBuilder("\"");
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      escaped.append(switch (character) {
        case '"' -> "\\\"";
        case '\\' -> "\\\\";
        case '\n' -> "\\n";
        case '\r' -> "\\r";
        case '\t' -> "\\t";
        default -> character < 32 ? String.format("\\u%04x", (int) character) : String.valueOf(character);
      });
    }
    return escaped.append('"').toString();
  }
}
