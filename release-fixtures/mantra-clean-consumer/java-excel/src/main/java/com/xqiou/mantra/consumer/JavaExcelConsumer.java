package com.xqiou.mantra.consumer;

import com.xqiou.mantra.core.Mantra;
import com.xqiou.mantra.core.api.BatchOptions;
import com.xqiou.mantra.core.api.BatchSummary;
import com.xqiou.mantra.core.api.CalculationOptions;
import com.xqiou.mantra.core.api.CalculationResult;
import com.xqiou.mantra.core.api.CompiledCalculation;
import com.xqiou.mantra.core.model.CaseData;
import com.xqiou.mantra.core.model.Schema;
import com.xqiou.mantra.core.model.Value;
import com.xqiou.mantra.core.read.SourceText;
import com.xqiou.mantra.excel.ExcelExport;
import com.xqiou.mantra.excel.ExcelOptions;
import com.xqiou.mantra.excel.ExcelWorkbook;
import com.xqiou.mantra.render.Render;
import com.xqiou.normein.dsl.runtime.DslBudgetCounter;
import kotlin.Unit;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class JavaExcelConsumer {
    private static CaseData typed(String id, String value) {
        return new CaseData(id, "consumer/example", Map.of(), Map.of("base", new Value.Num(new BigDecimal(value))),
            Map.of(), Map.of(), Map.of(), List.of(), "typed-java", Map.of(), Map.of(), List.of(),
            Map.of(), Map.of(), List.of(), Map.of());
    }

    public static void main(String[] args) throws Exception {
        Schema schema = Mantra.INSTANCE.loadSchema(new SourceText("schema.mantra",
            "(schema consumer/example (input base :decimal) (line answer \"Answer\" (+ base 1)))", null),
            (path, relative) -> null);
        CalculationOptions options = new CalculationOptions(new com.xqiou.mantra.core.api.RunLimits(),
            new com.xqiou.mantra.core.api.RunControl(), Map.of(DslBudgetCounter.FUNCTION_CALLS, 10L));
        CompiledCalculation compiled = Mantra.INSTANCE.compile(schema, CaseData.Companion.empty("bindings"), List.of(), options);
        CalculationResult result = compiled.calculate(typed("java-a", "5"), List.of(), options);
        if (((Value.Num) result.value("answer")).getValue().compareTo(new BigDecimal("6")) != 0)
            throw new AssertionError("Typed current result differs");
        long[] seen = {0};
        BatchSummary batch = compiled.forEach(List.of(typed("java-b", "7"), typed("java-c", "9")), List.of(),
            new BatchOptions(), item -> {
                if (!item.getSucceeded() || item.getResult() == null) throw new AssertionError(item.getDiagnostics());
                seen[0]++;
                return Unit.INSTANCE;
            });
        if (seen[0] != 2 || batch.getCompletedCases() != 2 || batch.getStatistics().getExecutionPlanCompilations() != 0)
            throw new AssertionError("Batch did not physically reuse compilation");
        try (ExcelWorkbook generated = ExcelExport.INSTANCE.workbook(result, Render.INSTANCE.defaultLayout(result), new ExcelOptions())) {
            // This explicit external type must resolve from the Excel POM's compile dependencies.
            XSSFWorkbook nativeWorkbook = generated.getWorkbook();
            if (nativeWorkbook.getNumberOfSheets() == 0 || generated.bytes(1048576).length == 0)
                throw new AssertionError("Workbook was not generated");
        }
        System.out.println("MANTRA_JAVA_EXCEL_CONSUMER_OK");
    }
}
