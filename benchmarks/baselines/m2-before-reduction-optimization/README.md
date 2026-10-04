# M2 before reduction optimization

Measured clean source revision: `fe5bf8eceb5e02b11d6de65a064ca7db991e47be`.
The original M0/M1 workload and JVM settings were retained: five warmups and ten samples
for each of six operations in five scenarios, giving 300 samples and 30 summaries.

All 15,265 calculated coordinates matched independent arithmetic and cached XLSX values,
with zero formula fallbacks or evaluation errors. This archive preserves the regression
found before simplifying unconditional Excel aggregation and caching export expressions.
It is historical evidence, not the final optimized M2 baseline or a declared time budget.

The source fingerprint, environment, raw samples and per-scenario verification are retained.
An independent check recomputed the fingerprint, medians, p95 values and heap summaries.
Generated HTML/Text/XLSX files remain in the ignored benchmark build directory.
