import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val directory = Path.of("docs/site/examples/invoice")
    val schema = Mantra.loadSchema(directory.resolve("schema.mantra"))
    val case = Mantra.loadCase(directory.resolve("case.mantra"))
    val parameters = listOf(Mantra.loadParameters(directory.resolve("parameters.mantra")))
    val layout = Render.loadLayout(directory.resolve("layout.mantra"))
    val result = Mantra.calculateForAudit(schema, case, parameters)
    check(result.succeeded) { result.diagnostics.joinToString("\n") }
    check(result.validationPassed)
    check(result.decimal("charge").compareTo(BigDecimal("300.00")) == 0)
    println(Render.text(result, layout, includeAudit = true))
    val output = Path.of("build/tutorial")
    Files.createDirectories(output)
    Files.writeString(output.resolve("paper.html"), Render.html(result, layout))
    ExcelExport.workbook(result, layout).use { workbook ->
        check(workbook.report.fallbacks.isEmpty()) { workbook.report.fallbacks.toString() }
        check(workbook.report.evaluationErrors.isEmpty()) { workbook.report.evaluationErrors.toString() }
        workbook.write(output.resolve("workbook.xlsx"))
    }
}
