import com.xqiou.mantra.benchmarks.Scenario;
import com.xqiou.mantra.benchmarks.SyntheticFixture;
import com.xqiou.mantra.core.Mantra;
import com.xqiou.mantra.core.api.AuditOptions;
import com.xqiou.mantra.core.api.CalculationOptions;
import com.xqiou.mantra.core.api.CalculationResult;
import com.xqiou.mantra.core.api.RunControl;
import com.xqiou.mantra.core.api.RunLimits;
import com.xqiou.mantra.excel.ExcelExport;
import com.xqiou.mantra.excel.ExcelOptions;
import com.xqiou.mantra.excel.ExcelWorkbook;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Diagnostic consumer that records only the complete public XLSX export operation. */
public final class CombinedXlsxProfile {
    private static volatile byte[] retained;

    private static void export(SyntheticFixture fixture, CalculationResult audited, ExcelOptions options) throws Exception {
        try (ExcelWorkbook workbook = ExcelExport.INSTANCE.workbook(audited, fixture.getLayout(), options)) {
            retained = workbook.bytes(64 * 1024 * 1024);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output recording.jfr path");
        SyntheticFixture fixture = new SyntheticFixture(new Scenario("combined", 250, 50, 1000));
        CalculationResult audited = Mantra.INSTANCE.calculateForAudit(
            fixture.getSchema(), fixture.getCase(), List.of(), new AuditOptions(), new CalculationOptions());
        fixture.verify(audited);
        RunLimits readLimits = new RunLimits(64L, 16L, 1024L, 100000L, 5000000L, 100000L,
            1000000L, 100000L, 10000000L, 64L * 1024 * 1024, Duration.ofMinutes(5));
        ExcelOptions options = new ExcelOptions(true, true, true, 16, 250000, 100000,
            new CalculationOptions(readLimits, new RunControl(), Map.of()), Map.of());
        // Actual numeric/cached-value verification and one warmup are outside the recording.
        try (ExcelWorkbook workbook = ExcelExport.INSTANCE.workbook(audited, fixture.getLayout(), options)) {
            fixture.verify(workbook);
            retained = workbook.bytes(64 * 1024 * 1024);
        }
        retained = null;
        System.gc(); // Same explicit pre-sample request as the reference harness; outside capture.
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.setName("mantra-combined-xlsx");
            recording.setMaxSize(512L * 1024 * 1024);
            recording.start();
            long started = System.nanoTime();
            export(fixture, audited, options);
            long elapsed = System.nanoTime() - started;
            recording.stop();
            recording.dump(Path.of(args[0]));
            System.out.println("Captured combined XLSX build/recalculation/serialization/close ns=" + elapsed
                + " bytes=" + retained.length);
        }
        retained = null;
    }
}
