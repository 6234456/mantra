import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const readJson = async (path) => JSON.parse(await readFile(path, "utf8"));

async function loadRuntime(source, dependencies) {
  const require = createRequire(resolve(dependencies, "package.json"));
  const esbuild = require("esbuild");
  const modules = {
    reader: "packages/excel-io/src/import-ir-reader.ts",
    plan: "packages/office-import-core/src/workbook-import-plan.ts",
    upload:
      "apps/manifest-runtime/src/document-library/createUploadedXlsxPackage.ts",
    calculation:
      "packages/office-workbook-editor-core/src/workbook-calculation-runtime.ts",
    model: "packages/office-model-v2/src/index.ts",
    formula: "packages/formula-engine/src/index.ts",
  };
  const names = {
    reader: ["readExcelWorkbookImportIR"],
    plan: ["createWorkbookImportPlanV2"],
    upload: ["createUploadedXlsxPackage"],
    calculation: ["createWorkbookCalculationRuntime"],
    model: [
      "getWorkbookCellTreeCellByRefV2",
      "workbookCellTreeEntriesV2",
      "setNormalizedWorkbookCellTreeCellV2",
    ],
    formula: [
      "evaluateFormula",
      "parseFormula",
      "parseCellRef",
      "formatCellRef",
      "normalizeValue",
      "FORMULA_FUNCTION_METADATA",
    ],
  };
  const entry = Object.entries(modules)
    .map(
      ([key, path]) =>
        `export {${names[key].join(",")}} from ${JSON.stringify(resolve(source, path))};`,
    )
    .join("\n");
  const output = resolve(dependencies, "audit-runtime-bundle.mjs");
  const bundled = await esbuild.build({
    stdin: { contents: entry, resolveDir: source, loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    target: "node24",
    outfile: output,
    metafile: true,
    external: ["exceljs", "jszip", "saxes"],
    plugins: [
      {
        name: "frozen-template-engine-workspaces",
        setup(build) {
          build.onResolve(
            { filter: /^@template-engine\// },
            async ({ path }) => {
              const [name, ...parts] = path
                .slice("@template-engine/".length)
                .split("/");
              const packageRoot = resolve(source, "packages", name);
              const packageInfo = await readJson(
                resolve(packageRoot, "package.json"),
              );
              const key = parts.length ? `./${parts.join("/")}` : ".";
              const exported = packageInfo.exports?.[key];
              const relative =
                typeof exported === "string"
                  ? exported
                  : (exported?.import ?? packageInfo.main);
              if (!relative)
                throw new Error(`No frozen workspace entry for ${path}`);
              return { path: resolve(packageRoot, relative) };
            },
          );
        },
      },
    ],
  });
  const inputs = await Promise.all(
    Object.keys(bundled.metafile.inputs)
      .filter((path) => path !== "<stdin>")
      .map(async (path) => {
        const absolute = resolve(path);
        return {
          path: absolute.slice(source.length + 1),
          sha256: hash(await readFile(absolute)),
        };
      }),
  );
  inputs.sort((left, right) => left.path.localeCompare(right.path));
  return {
    api: await import(pathToFileURL(output).href),
    inputs,
    bundleSHA256: hash(await readFile(output)),
  };
}

function serializable(value) {
  if (typeof value === "number" && !Number.isFinite(value))
    return { nonFinite: String(value) };
  if (value && typeof value === "object") {
    if (Array.isArray(value)) return value.map(serializable);
    return Object.fromEntries(
      Object.entries(value).map(([key, entry]) => [key, serializable(entry)]),
    );
  }
  return value;
}

function address(api, workbook, mapping) {
  const match = mapping.address?.match(
    /^(?:'((?:[^']|'')+)'|([^!]+))!(\$?[A-Z]+\$?\d+)$/,
  );
  if (!match) throw new Error(`Unmapped source address: ${mapping.address}`);
  const sheetName = match[1]?.replaceAll("''", "'") ?? match[2];
  const sheet = Object.values(workbook.sheets).find(
    (candidate) => candidate.name === sheetName,
  );
  const coordinate = api.parseCellRef(match[3]);
  if (!sheet || !coordinate)
    throw new Error(`Imported address unavailable: ${mapping.address}`);
  return { sheet, cell: api.formatCellRef(coordinate), coordinate };
}

function expectedPrimitive(value) {
  if (value.type === "decimal") return Number(value.text);
  if (value.type === "boolean") return value.value;
  if (value.type === "text" || value.type === "keyword") return value.text;
  if (value.type === "nil") return null;
  throw new Error(
    `No implicit conversion is allowed for source type ${value.type}`,
  );
}

function mappingIdentity(mapping) {
  return JSON.stringify([
    mapping.kind,
    mapping.node,
    mapping.coord,
    mapping.rowIndex ?? null,
    mapping.column ?? null,
  ]);
}

function inputChanges(base, edited) {
  const after = new Map(
    edited.mappings.map((mapping) => [mappingIdentity(mapping), mapping]),
  );
  return base.mappings
    .filter((mapping) => mapping.editableInput && mapping.address)
    .flatMap((mapping) => {
      const next = after.get(mappingIdentity(mapping));
      if (!next || JSON.stringify(next.value) === JSON.stringify(mapping.value))
        return [];
      return [{ mapping, before: mapping.value, after: next.value }];
    });
}

function calculate(api, runtime, workbook) {
  const active =
    workbook.sheets[workbook.activeSheetId ?? workbook.sheetOrder[0]];
  return runtime.calculate({
    workbookId: workbook.id,
    sheetId: active.id,
    cells: active.cells,
    worksheets: workbook.sheets,
  });
}

function evidence(api, calculation, workbook, provenance) {
  const formulas = [];
  for (const sheet of Object.values(workbook.sheets)) {
    for (const entry of api.workbookCellTreeEntriesV2(sheet.cells)) {
      if (entry.cell.content.kind !== "formula") continue;
      const cell = api.formatCellRef({
        row: entry.rowIndex,
        col: entry.columnIndex,
      });
      let parserError;
      try {
        api.parseFormula(entry.cell.content.formula);
      } catch (error) {
        parserError = error.message;
      }
      formulas.push({
        sheet: sheet.name,
        cell,
        formula: entry.cell.content.formula,
        ...(parserError ? { parserError } : {}),
        result: serializable(
          api.normalizeValue(calculation.resolveSheetCellValue(sheet.id, cell)),
        ),
      });
    }
  }
  const sourceValues = provenance.mappings
    .filter((mapping) => mapping.address && mapping.value.type === "decimal")
    .map((mapping) => {
      const target = address(api, workbook, mapping);
      return {
        kind: mapping.kind,
        node: mapping.node,
        coord: mapping.coord,
        rowIndex: mapping.rowIndex,
        column: mapping.column,
        address: mapping.address,
        expectedExactDecimal: mapping.value.text,
        editableInput: mapping.editableInput,
        actual: serializable(
          api.normalizeValue(
            calculation.resolveSheetCellValue(target.sheet.id, target.cell),
          ),
        ),
      };
    });
  const sourceChecks = provenance.mappings
    .filter(
      (mapping) =>
        mapping.address &&
        mapping.value.type === "boolean" &&
        !mapping.editableInput,
    )
    .map((mapping) => {
      const target = address(api, workbook, mapping);
      return {
        node: mapping.node,
        coord: mapping.coord,
        address: mapping.address,
        expectedBoolean: mapping.value.value,
        actual: serializable(
          api.normalizeValue(
            calculation.resolveSheetCellValue(target.sheet.id, target.cell),
          ),
        ),
      };
    });
  return {
    formulas,
    sourceValues,
    sourceChecks,
    formulaErrors: formulas.filter(
      (cell) => cell.result.kind === "error" || cell.parserError,
    ),
  };
}

async function auditArtifact(api, specification) {
  const bytes = await readFile(specification.xlsx);
  const provenance = await readJson(specification.provenance);
  const edited = await readJson(specification.editedProvenance);
  const result = { id: specification.id, xlsxSHA256: hash(bytes) };
  try {
    const ir = await api.readExcelWorkbookImportIR(bytes);
    result.importIR = ir;
    // Run the application upload/conversion path itself; also keep the plan diagnostics it discards.
    const model = await api.createUploadedXlsxPackage(
      bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength),
      `mantra-audit-${specification.id}`,
      `Mantra ${specification.id}`,
    );
    const workbook = model.workbooks[model.entry.workbookId];
    const plan = api.createWorkbookImportPlanV2({
      workbook: { ...workbook, sheets: {}, sheetOrder: [] },
      importIR: ir,
    });
    result.importPlan = {
      diagnostics: plan.diagnostics,
      worksheets: plan.worksheets,
      comments: plan.comments,
      activeSheetId: plan.activeSheetId,
    };
    result.importedWorkbook = workbook;
    const runtime = api.createWorkbookCalculationRuntime(
      `mantra-audit-${specification.id}`,
    );
    result.initial = evidence(
      api,
      calculate(api, runtime, workbook),
      workbook,
      provenance,
    );
    const changes = inputChanges(provenance, edited);
    if (!changes.length)
      throw new Error(
        "A genuine changed input is required for a recalculation proof",
      );
    let changedWorkbook = { ...workbook, sheets: { ...workbook.sheets } };
    result.inputChanges = changes.map((change) => ({
      ...change,
      convertedPrimitive: serializable(expectedPrimitive(change.after)),
    }));
    for (const change of changes) {
      const target = address(api, changedWorkbook, change.mapping);
      const cell = api.getWorkbookCellTreeCellByRefV2(
        target.sheet.cells,
        target.cell,
      );
      if (!cell || cell.content.kind === "formula")
        throw new Error("Only source-bound input cells may be edited");
      const next = api.setNormalizedWorkbookCellTreeCellV2(
        target.sheet.cells,
        { rowIndex: target.coordinate.row, columnIndex: target.coordinate.col },
        {
          ...cell,
          content: { kind: "scalar", value: expectedPrimitive(change.after) },
        },
      );
      changedWorkbook.sheets[target.sheet.id] = {
        ...target.sheet,
        cells: next.tree,
      };
    }
    result.edited = evidence(
      api,
      calculate(api, runtime, changedWorkbook),
      changedWorkbook,
      edited,
    );
    result.runtimeSnapshot = runtime.getSnapshot();
    result.importSucceeded = true;
  } catch (error) {
    result.importSucceeded = Boolean(result.importedWorkbook);
    result.failure = { name: error.name, message: error.message };
  }
  return result;
}

async function main() {
  const [source, dependencies, requestPath, outputPath] = process.argv.slice(2);
  if (!outputPath)
    throw new Error(
      "Expected frozen-source dependencies request.json output.json",
    );
  const { api, inputs, bundleSHA256 } = await loadRuntime(
    resolve(source),
    resolve(dependencies),
  );
  const request = await readJson(requestPath);
  const expectedDependencies = {
    esbuild: "0.25.12",
    exceljs: "4.4.0",
    jszip: "3.10.1",
    saxes: "6.0.0",
  };
  const actualDependencies = await Promise.all(
    Object.entries(expectedDependencies).map(async ([name, version]) => {
      const bytes = await readFile(
        resolve(dependencies, "node_modules", name, "package.json"),
      );
      const metadata = JSON.parse(bytes);
      if (metadata.version !== version)
        throw new Error(
          `Expected ${name}@${version}, loaded ${metadata.version}`,
        );
      return {
        name,
        version: metadata.version,
        packageMetadataSHA256: hash(bytes),
      };
    }),
  );
  const probes = [
    'EXACT("A","A")',
    "ISBLANK(A1)",
    "ISLOGICAL(TRUE)",
    'ISTEXT("A")',
    "ROUND(-1.5,0)",
    "ROUND(1.005,2)",
    "0.1+0.2",
    "9007199254740992+1",
  ];
  const probeResults = probes.map((formula) => ({
    formula,
    actual: serializable(
      api.evaluateFormula(formula, { getCellValue: () => null }),
    ),
  }));
  const artifacts = [];
  for (const specification of request.artifacts)
    artifacts.push(await auditArtifact(api, specification));
  await writeFile(
    outputPath,
    JSON.stringify(
      {
        format: "mantra.template-engine-runtime-audit/1",
        templateEngineCommit: request.templateEngineCommit,
        sourceModules: inputs,
        bundleSHA256,
        installedDependencyLockSHA256: hash(
          await readFile(resolve(dependencies, "package-lock.json")),
        ),
        capabilities: api.FORMULA_FUNCTION_METADATA,
        actualDependencies,
        probes: probeResults,
        artifacts,
      },
      null,
      2,
    ) + "\n",
  );
}

main().catch((error) => {
  process.stderr.write(`${error.stack}\n`);
  process.exitCode = 1;
});
