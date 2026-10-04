# M2 continuous periods before reduction optimization

Measured clean source revision: `fe5bf8eceb5e02b11d6de65a064ca7db991e47be`.
The workload contains 200 series and ten generated periods. It checks 6,200 scalar
coordinates and 633 reductions using independent closed-form arithmetic. Matrix and
transpose exports have zero formula fallbacks and evaluation errors.

Five warmups and ten samples for nine operations give 90 samples. Incremental edit and
unchanged-repeat samples include real task, formula and retained-session counters.
This archive precedes the generic Excel reduction optimization; it is not a final time budget.

The source fingerprint, environment, raw samples and verification are retained. An
independent check recomputed the fingerprint, timing/heap summaries and zero-work repeats.
Generated HTML/Text/XLSX files remain in the ignored benchmark build directory.
