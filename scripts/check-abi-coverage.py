#!/usr/bin/env python3
"""Require real full-surface ABI baselines; this does not generate or rewrite a baseline."""
from pathlib import Path
import argparse
import sys

REQUIRED = {
    "mantra-core": ["core/Mantra", "core/Diagnostic", "core/SourceLocation", "core/model/Value", "core/model/CaseData", "core/model/Schema", "core/read/ParameterSet", "core/read/SourceResolver", "core/api/CalculationOptions", "core/api/CompiledCalculation"],
    "mantra-render": ["render/Render", "render/layout/LayoutSpec", "render/paper/WorkingPaper"],
    "mantra-excel": ["excel/ExcelWorkbook", "excel/ExcelOptions", "org/apache/poi/xssf/usermodel/XSSFWorkbook"],
    "mantra-workbench": ["workbench/WorkspaceCatalog", "workbench/CasePackageLoader", "render/layout/LayoutSpec"],
    "mantra-server": ["server/WorkbenchServer", "workbench/ExportBudget", "workbench/packages/PackageWorkspaceCatalog"],
    "mantra-packages": ["packages/PackageLoader", "packages/PackageSnapshot", "packages/PackageSchemaBinding", "core/model/SchemaIdentity", "packages/MigrationRuntime"],
}


def check(root: Path) -> list[str]:
    problems = []
    for module, required in REQUIRED.items():
        dumps = sorted((root / module / "api").rglob("*.api"))
        if not dumps:
            problems.append(f"{module}: no generated, reviewed ABI baseline; run updateLegacyAbi explicitly")
            continue
        contents = "\n".join(path.read_text(encoding="utf-8") for path in dumps).replace(".", "/")
        for symbol in required:
            if symbol not in contents:
                problems.append(f"{module}: public/reachable ABI evidence missing: {symbol}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    problems = check(args.root)
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print("Full library ABI baselines cover model/read/render/workbench and public POI signatures")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
