# Circular calculation demonstrations

**Uncompiled M3 application draft.** These are two neutral fictional money agreements, not payroll, tax or production financial software. No engine, rendering or XLSX acceptance has been run for the draft. The application is not published as a library artifact.

bonus/ contains demo.bonus@1.0 and gross-up/ contains demo.gross-up@1.0. Each has its own schema, unique parameter ID and unique layout ID. All calculations use the public calc/converge function and ordinary input, line and check primitives. No host code recognizes these domains or case IDs.

The bonus callback rounds rate times (profit minus current bonus) to cents on every actual call. The main amount is EUR 9,090.91 for fictional profit EUR 100,000 and fraction 0.10. Zero rate, zero profit and signed profit are separate boundaries. Rate 1 creates a two-cycle; a EUR 0.01 profit with rate 0.5 creates a cent-rounding cycle. Two calls are insufficient for the main fixture.

The gross-up callback rounds requested net plus the fictional flat fraction times current gross to cents on every actual call. The declared seed matters: net EUR 1,000 and fraction 0.25 reaches EUR 1,333.33 from the ordinary seed and EUR 1,333.34 from seed EUR 2,000. Both are valid cent fixed points. The exact equation has rational solution 4000/3; that is separate source evidence, not a fake runtime trace. A coarse adjacent-iterate tolerance returns EUR 1,333.01 but has a money equation residual of EUR -0.24 and therefore an explicit BUSINESS error. Rate 1 with positive net has no solution; rate 1.25 with the declared positive seed diverges. These facts do not imply that every rate-1 agreement is unsolvable: net zero has identity fixed points and belongs in the generic kernel contract tests.

The maximum-call count and stopping tolerance are explicit per-case facts, not human-approved global run-budget defaults. Call the callback first, then accept the next result when the adjacent difference is within tolerance. Every callback rounds explicitly to two digits; the separate equation check has tolerance EUR 0.01.

Eleven ordinary cases require full common acceptance, including the coarse-tolerance BUSINESS failure. Six deliberately failing cases live in failure-cases/ and need targeted public-API tests for MANTRA-CALC-NOT-CONVERGED, a technically failed result, no converged numeric amount, and generic workbench failure presentation. They must not be turned into successful zero-valued cases or silently excluded from all verification. The shared ordinary-case runner must retain its success requirement.

independent/ preserves byte-identical fictional facts, frozen references and the original Python Decimal/Fraction algorithm. It checks exact rational roots, independently derived cent candidates, and the declared seed/stop orbit. Orbit edges are explicitly labeled source certificates, never runtime calculation steps. Acceptance must inspect genuine FULL calculation steps and matching callback counts without replaying the source orbit as trace.

The current M3 contract requires dynamic bounded hidden XLSX iteration cells with editable inputs and zero fallback for these demonstrations. An exported workbook must recalculate after seed/base/rate changes, including real non-convergence. Global Excel iterative-calculation settings and static cached values do not satisfy this requirement.

After migration run:

    python3 apps/circular-calculation/verify_m3_expected.py --self-test
    python3 apps/circular-calculation/verify_m3_expected.py --outputs apps/circular-calculation/build/out

Between those commands run common public-API acceptance for eleven ordinary cases and six targeted failure tests. Ordinary cases must render HTML/Text/strict Workbench JSON/XLSX, compare all meaningful values independently, and open through the generic workbench. Genuine callback traces, edited-input workbook recalculation and failure UI assertions remain required; this draft is not their evidence.
