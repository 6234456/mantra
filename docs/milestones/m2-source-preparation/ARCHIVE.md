# Independent preparation provenance

The adjacent README, migration notes and SHA-256 manifest preserve the original fictional
application preparation collected before M2 implementation. They describe that historical
temporary directory; their commands and proposed addresses are not the current repository API.

The two applications now live in `apps/fixed-assets` and `apps/ifrs-leases`. The original
73 application source/reference files were retained there, with explicit fiscal-year keys
mapped to generated `P1`–`Pn` period keys. All 24 case/precision numeric sources preserve their
independently computed amounts. The single-period lease also has explicit zero-valued second
and third periods. The manifest records the original bytes, not the migrated bytes.

Implementation and actual acceptance results are recorded separately in
[M2 work packages](../m2-work-packages.md). The original temporary directory can be removed
after archiving these provenance files.
