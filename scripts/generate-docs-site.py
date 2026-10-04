#!/usr/bin/env python3
"""Build a self-contained documentation snapshot using only Python's standard library."""

import argparse
import hashlib
import html
from html.parser import HTMLParser
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
from urllib.parse import quote, unquote, urlsplit
import zipfile


ROOT = Path(__file__).resolve().parents[1]
GENERATOR = "mantra-docs-site/1"
NAVIGATION = [
    ("Overview", "index.html"), ("Your first schema", "tutorial.html"),
    ("Embed in Kotlin", "embedding.html"), ("DSL reference", "reference/dsl.html"),
    ("Functions", "reference/functions.html"), ("Diagnostics", "reference/diagnostics.html"),
    ("Public API", "reference/api.html"), ("Applications", "apps/index.html"),
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def slug(text):
    plain = re.sub(r"[`*_]", "", text).lower()
    return re.sub(r"[^\w\s-]", "", plain).replace(" ", "-")


def table_cells(line):
    """Keep pipes inside inline code and escaped pipes in the same table cell."""
    cells = []
    current = []
    code = False
    escaped = False
    for character in line.strip().removeprefix("|").removesuffix("|"):
        if escaped:
            if character != "|":
                current.append("\\")
            current.append(character)
            escaped = False
        elif character == "\\":
            escaped = True
        elif character == "`":
            code = not code
            current.append(character)
        elif character == "|" and not code:
            cells.append("".join(current))
            current = []
        else:
            current.append(character)
    if escaped:
        current.append("\\")
    return cells + ["".join(current)]


class DocumentLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.ids = set()

    def handle_starttag(self, tag, attributes):
        values = dict(attributes)
        if "id" in values:
            if values["id"] in self.ids:
                raise ValueError(f"Duplicate HTML anchor: {values['id']}")
            self.ids.add(values["id"])
        for name in ("href", "src"):
            if name in values:
                self.links.append(values[name])


def check_links(output):
    output = output.resolve()
    documents = {}
    for path in sorted(output.rglob("*.html")):
        parser = DocumentLinks()
        parser.feed(path.read_text(encoding="utf-8"))
        documents[path.resolve()] = parser
    if not documents:
        raise ValueError(f"No generated HTML documents: {output}")
    failures = set()
    checked = 0
    for path, parser in documents.items():
        for link in parser.links:
            parsed = urlsplit(link)
            if parsed.scheme or parsed.netloc:
                if parsed.scheme not in ("https", "http", "mailto"):
                    failures.add(f"{path}: unsupported URL {link}")
                continue
            target = (path.parent / unquote(parsed.path)).resolve() if parsed.path else path
            if not target.is_relative_to(output.resolve()) or not target.is_file():
                failures.add(f"{path}: missing/confined link {link}")
                continue
            checked += 1
            if parsed.fragment and target in documents:
                if unquote(parsed.fragment) not in documents[target].ids:
                    failures.add(f"{path}: missing anchor {link}")
    if failures:
        raise ValueError("\n".join(sorted(failures)))
    return {"html_documents": len(documents), "local_links": checked}


class Site:
    def __init__(self, root, output):
        self.root = root.resolve()
        self.source = self.root / "docs/site"
        self.output = output.resolve()
        self.sources = {}
        self.artifacts = []
        self.artifact_issues = []
        self.rewrites = {
            self.root / "docs/dsl-reference.md": "reference/dsl.html",
            self.root / "docs/diagnostics.md": "reference/diagnostics.html",
        }

    def read(self, path):
        path = path.resolve()
        if not path.is_relative_to(self.root):
            raise ValueError(f"Source outside repository: {path}")
        self.sources[path.relative_to(self.root).as_posix()] = digest(path)
        return path.read_text(encoding="utf-8")

    def local(self, target, page):
        target = (self.output / target).resolve()
        if not target.is_relative_to(self.output):
            raise ValueError(f"Generated link leaves site: {target}")
        return quote(os.path.relpath(target, (self.output / page).parent).replace(os.sep, "/"), safe="/")

    def source_file(self, path, page):
        path = path.resolve()
        if not path.is_relative_to(self.root) or not path.is_file():
            raise ValueError(f"Cannot publish source file: {path}")
        relative = path.relative_to(self.root)
        if any(part in (".git", ".deps", ".agents", ".codex", ".aws") for part in relative.parts):
            raise ValueError(f"Private/internal checkout source cannot be published: {relative}")
        destination = Path("sources") / relative
        target = self.output / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        self.sources[relative.as_posix()] = digest(path)
        return self.local(destination, page)

    def link(self, value, origin, page):
        parsed = urlsplit(value)
        if parsed.scheme in ("https", "http", "mailto"):
            return value
        if parsed.netloc or parsed.scheme not in ("", "repo", "site"):
            raise ValueError(f"Unsupported documentation URL: {value}")
        if parsed.scheme == "site":
            target = self.local(parsed.path, page)
        elif parsed.path:
            path = (self.root / unquote(parsed.path) if parsed.scheme == "repo" else origin.parent / unquote(parsed.path)).resolve()
            target = self.local(self.rewrites[path], page) if path in self.rewrites else self.source_file(path, page)
        else:
            target = ""
        return target + ("#" + parsed.fragment if parsed.fragment else "")

    def inline(self, text, origin, page):
        tokens = []

        def preserve(value):
            tokens.append(value)
            return f"\x00{len(tokens) - 1}\x00"

        def markup(match):
            if match[1] is not None:
                return preserve("<code>" + html.escape(match[1]) + "</code>")
            return preserve('<a href="' + html.escape(self.link(match[3], origin, page), quote=True) + '">' +
                            self.inline(match[2], origin, page) + "</a>")

        text = re.sub(r"`([^`]+)`|\[([^\]]+)\]\(([^)]+)\)", markup, text)
        text = html.escape(text)
        text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
        text = re.sub(r"\x00(\d+)\x00", lambda m: tokens[int(m[1])], text)
        return text

    def markdown(self, text, origin, page):
        def include(match):
            source = self.source / match[1]
            return "```" + match[2] + "\n" + self.read(source).rstrip() + "\n```"

        text = re.sub(r"\{\{file ([\w/.-]+) ([\w+-]+)\}\}", include, text)
        lines = text.splitlines()
        rendered = []
        anchors = {}
        index = 0
        while index < len(lines):
            line = lines[index]
            if not line.strip():
                index += 1
                continue
            if line.startswith("```"):
                language = line[3:].strip()
                index += 1
                code = []
                while index < len(lines) and not lines[index].startswith("```"):
                    code.append(lines[index])
                    index += 1
                if index == len(lines):
                    raise ValueError(f"Unclosed code fence: {origin}")
                rendered.append('<pre><code class="language-' + html.escape(language, quote=True) + '">' +
                                html.escape("\n".join(code)) + "</code></pre>")
                index += 1
                continue
            heading = re.match(r"^(#{1,6}) (.+)$", line)
            if heading:
                label = slug(heading[2])
                count = anchors.get(label, 0)
                anchors[label] = count + 1
                identifier = label + (f"-{count}" if count else "")
                level = len(heading[1])
                rendered.append(f'<h{level} id="{identifier}">' + self.inline(heading[2], origin, page) + f"</h{level}>")
                index += 1
                continue
            if line.startswith("|") and index + 1 < len(lines) and re.match(r"^\|[\s:|-]+\|$", lines[index + 1]):
                rows = []
                while index < len(lines) and lines[index].startswith("|"):
                    rows.append(table_cells(lines[index]))
                    index += 1
                cells = lambda row, tag: "".join(f"<{tag}>" + self.inline(cell.strip(), origin, page) + f"</{tag}>" for cell in row)
                rendered.append('<div class="table-scroll"><table><thead><tr>' + cells(rows[0], "th") +
                                "</tr></thead><tbody>" + "".join("<tr>" + cells(row, "td") + "</tr>" for row in rows[2:]) +
                                "</tbody></table></div>")
                continue
            if re.match(r"^(?:- |\d+\. )", line):
                ordered = bool(re.match(r"^\d+\. ", line))
                pattern = r"^\d+\. (.+)" if ordered else r"^- (.+)"
                items = []
                while index < len(lines) and (item := re.match(pattern, lines[index])):
                    items.append("<li>" + self.inline(item[1], origin, page) + "</li>")
                    index += 1
                tag = "ol" if ordered else "ul"
                rendered.append(f"<{tag}>" + "".join(items) + f"</{tag}>")
                continue
            paragraph = []
            while index < len(lines) and lines[index].strip():
                if paragraph and re.match(r"^(?:#{1,6} |```|\||- |\d+\. )", lines[index]):
                    break
                paragraph.append(lines[index])
                index += 1
            rendered.append("<p>" + self.inline(" ".join(paragraph), origin, page) + "</p>")
        return "\n".join(rendered)

    def page(self, path, title, text, origin):
        navigation = "".join('<a' + (' aria-current="page"' if destination == path else "") +
                             ' href="' + self.local(destination, path) + '">' + label + "</a>"
                             for label, destination in NAVIGATION)
        body = self.markdown(text, origin, path)
        document = '<!doctype html>\n<html lang="en"><head><meta charset="utf-8">' + \
            '<meta name="viewport" content="width=device-width, initial-scale=1">' + \
            '<meta name="color-scheme" content="light dark"><title>' + html.escape(title) + \
            ' · Mantra</title><link rel="stylesheet" href="' + self.local("assets/site.css", path) + '"></head>' + \
            '<body><a class="skip" href="#content">Skip to content</a><header><a class="brand" href="' + \
            self.local("index.html", path) + '">MANTRA</a><span>Calculation schemas, explained.</span></header>' + \
            '<div class="frame"><nav aria-label="Documentation">' + navigation + '</nav><main id="content">' + \
            body + '</main></div><footer>Offline source snapshot · Pre-1.0 · Demonstrations only</footer></body></html>\n'
        target = self.output / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(document, encoding="utf-8")


def functions(site, catalog=None):
    directory = site.root / "mantra-core/src/main/kotlin/com/xqiou/mantra/core/engine"
    source = site.read(directory / "MantraLibrary.kt")
    pattern = r'function\(\s*"([\w/-]+)",\s*((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+),'
    entries = {name: "".join(json.loads(token) for token in re.findall(r'"(?:\\.|[^"\\])*"', summary))
               for name, summary in re.findall(pattern, source)}
    convergence = site.read(directory / "ConvergeFunction.kt")
    summary = re.search(r'summary\s*=\s*((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+),', convergence)
    if not summary or 'name = "calc/converge"' not in convergence:
        raise ValueError("Converge public registration cannot be extracted")
    entries["calc/converge"] = "".join(json.loads(token) for token in re.findall(r'"(?:\\.|[^"\\])*"', summary[1]))
    entries = {name: description for name, description in entries.items() if not name.startswith("mantra-internal/")}
    reference = site.read(site.root / "docs/dsl-reference.md")
    section = reference.split("### 1.4", 1)[1].split("## 2.", 1)[0]
    documented = set(re.findall(r"\((?:alloc|calc|dim|fin|table)/[a-z-]+", section))
    if set(entries) != {name[1:] for name in documented}:
        raise ValueError("Public calculation registrations and DSL function reference disagree")
    if catalog:
        actual = {name: summary.strip() for name, summary in re.findall(r"^\s+((?:alloc|calc|dim|fin|table)/[a-z-]+)\s+(.+)$", catalog.read_text(), re.M)}
        if entries != actual:
            raise ValueError("Executable mantra catalog differs from source extraction")
    version = re.search(r'SEMANTICS_VERSION: String = "([^"]+)"', source)[1]
    content = "# Calculation functions\n\nGenerated from the actual Mantra registrations and checked against the English DSL reference. "
    content += f"Library semantics: `mantra.calc@{version}`. Internal lowering helpers are excluded.\n\n"
    content += "The composed Normein standard library is also available. Use `mantra catalog` to inspect the executable environment; "
    content += "this page lists the public Mantra domain-neutral calculation library.\n\n| Function | Registration summary |\n| --- | --- |\n"
    content += "\n".join(f"| `{name}` | {summary} |" for name, summary in sorted(entries.items()))
    return content, {"semantics": version, "functions": entries, "runtime_catalog_checked": catalog is not None}


def api_index(site):
    locations = ["mantra-core/src/main/kotlin/com/xqiou/mantra/core/api",
                 "mantra-core/src/main/kotlin/com/xqiou/mantra/core/view"]
    files = [path for directory in locations for path in sorted((site.root / directory).glob("*.kt"))]
    files += [site.root / path for path in (
        "mantra-core/src/main/kotlin/com/xqiou/mantra/core/Mantra.kt",
        "mantra-render/src/main/kotlin/com/xqiou/mantra/render/Render.kt",
        "mantra-excel/src/main/kotlin/com/xqiou/mantra/excel/ExcelExport.kt",
        "mantra-excel/src/main/kotlin/com/xqiou/mantra/excel/ExcelWorkbook.kt")]
    content = "# Public API index\n\nGenerated from public Kotlin declarations in this checkout. "
    content += "This is a declaration index, not a binary compatibility guarantee or a published artifact list. "
    content += "Open the defining source for signatures and lifecycle requirements.\n\n"
    content += "| Declaration | Defining source |\n| --- | --- |\n"
    count = 0
    for path in files:
        for number, line in enumerate(site.read(path).splitlines(), 1):
            declaration = re.match(r"^(?:(?:data|sealed|enum|fun|abstract) )?(?:class|interface|object|typealias) (\w+)", line)
            entrypoint = re.match(r"^    fun (\w+)\(", line) if path.name in ("Mantra.kt", "Render.kt", "ExcelExport.kt") else None
            name = declaration[1] if declaration else entrypoint[1] if entrypoint else None
            if name:
                relative = path.relative_to(site.root).as_posix()
                content += f"| `{name}` | [{path.name}:{number}](repo:{relative}) |\n"
                count += 1
    content += "\n## Planned M4 extensions\n\nCompiled schema handles, batch calculation, distributable scheme packages, "
    content += "parameter validity selection and compatibility gates remain roadmap work. "
    content += "No package or batch API is promised by this preparation. Update this section when the public contract lands.\n"
    return content, count


def diagnostic_inventory(site):
    """Use the repository's actual static-inventory rule instead of a separate directory."""
    checker = site.root / "scripts/check-diagnostics.py"
    site.read(checker)
    specification = importlib.util.spec_from_file_location("mantra_diagnostic_inventory", checker)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    problems = module.check(site.root)
    if problems:
        raise ValueError("Diagnostic directory does not match source: " + "; ".join(problems))
    codes = set()
    for path in site.root.glob("mantra-*/src/main/**/*.kt"):
        text = path.read_text(encoding="utf-8")
        found = module.CODE_LITERAL.findall(text)
        if found:
            site.read(path)
            codes.update(found)
    return {"static_codes": sorted(codes), "checked_against_source": True}


def application_page(site, application, require_artifacts):
    name = application["id"]
    directory = site.root / "apps" / name
    site.read(directory / "README.md")
    content = f"# {application['title']}\n\n{application['description']}\n\n"
    content += "Demonstration only. Fictional inputs and documented simplifications do not constitute production tax or accounting software. "
    content += "The schema owns domain rules; the engine stays generic.\n\n"
    content += f"[Application README](repo:apps/{name}/README.md) · "
    content += f"[Independent verification](repo:apps/{name}/{application['verification']})\n\n"
    content += "## Schema and case\n\n"
    content += f"[Open the schema](repo:apps/{name}/{application['schema']}) · "
    content += f"[Open the showcase case](repo:apps/{name}/{application['case']})\n\n"
    content += "## Generated outputs\n\nCopies below come from real application acceptance outputs; "
    content += "the documentation generator performs no financial calculation. Missing outputs are never invented.\n\n"
    stem = application["case"].removesuffix(".mantra")
    for extension, label in (("html", "HTML working paper"), ("txt", "Text working paper"), ("xlsx", "Editable workbook")):
        source = directory / "build/out" / (stem + "." + extension)
        if not source.is_file() or source.stat().st_size == 0:
            if require_artifacts:
                raise ValueError(f"Required application output is missing: {source}")
            content += f"- {label}: not generated in this checkout.\n"
            continue
        if extension == "xlsx":
            with zipfile.ZipFile(source) as workbook:
                if "xl/workbook.xml" not in workbook.namelist():
                    raise ValueError(f"Output is not an XLSX workbook: {source}")
        if extension == "html":
            parser = DocumentLinks()
            parser.feed(source.read_text(encoding="utf-8"))
            issues = set()
            for value in parser.links:
                parsed = urlsplit(value)
                if parsed.scheme in ("http", "https", "mailto"):
                    continue
                if parsed.scheme or parsed.netloc or parsed.path:
                    issues.add(f"unbundled or unsupported link: {value}")
                elif parsed.fragment and unquote(parsed.fragment) not in parser.ids:
                    issues.add(f"missing anchor: {value}")
            if issues:
                details = {"source": source.relative_to(site.root).as_posix(), "issues": sorted(issues)}
                if require_artifacts:
                    raise ValueError(f"Application HTML has invalid links: {details}")
                site.artifact_issues.append(details)
                content += f"- {label}: the existing output has invalid links. Regenerate after correcting the renderer.\n"
                continue
        target = Path("downloads") / name / (stem + "." + extension)
        (site.output / target).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, site.output / target)
        content += f"- [{label}](site:{target.as_posix()})\n"
        site.artifacts.append({"application": name, "source": source.relative_to(site.root).as_posix(),
                               "output": target.as_posix(), "sha256": digest(source)})
    content += f"\nGenerate these outputs with `./gradlew :apps:{name}:test`. "
    content += "CI retains `apps/*/build/out/` in its `verification-results` artifact. "
    content += "A directory's presence does not establish that all tests or the independent references passed; check the CI report.\n\n"
    content += f"Scope and verification focus: {application['focus']}\n"
    return content


def generate_content(root, output, require_artifacts=False, catalog=None):
    site = Site(root, output)
    site.output.mkdir(parents=True, exist_ok=True)
    (site.output / "assets").mkdir(exist_ok=True)
    shutil.copyfile(site.source / "site.css", site.output / "assets/site.css")
    site.read(site.source / "site.css")
    for name, title in (("index", "Overview"), ("tutorial", "Your first schema"), ("embedding", "Embed in Kotlin")):
        origin = site.source / (name + ".md")
        site.page(name + ".html", title, site.read(origin), origin)
    for name, title, source in (("dsl", "DSL reference", "dsl-reference.md"),
                                ("diagnostics", "Diagnostics", "diagnostics.md")):
        origin = site.root / "docs" / source
        site.page(f"reference/{name}.html", title, site.read(origin), origin)
    diagnostics = diagnostic_inventory(site)
    function_text, catalog_metadata = functions(site, catalog)
    site.page("reference/functions.html", "Functions", function_text, site.source / "index.md")
    api_text, declarations = api_index(site)
    site.page("reference/api.html", "Public API", api_text, site.source / "index.md")
    applications = json.loads(site.read(site.source / "applications.json"))
    actual = {path.name for path in (site.root / "apps").iterdir() if (path / "README.md").is_file()}
    if len(applications) != len(actual) or {item["id"] for item in applications} != actual:
        raise ValueError("Showcase configuration must cover every application README exactly once")
    gallery = "# Application gallery\n\nEach application combines public primitives with domain-owned schemas, "
    gallery += "parameters, layouts, fictional cases and independent verification. "
    gallery += "Applications are repository demonstrations and are not published as library artifacts.\n\n"
    for application in applications:
        name = application["id"]
        gallery += f"- [{application['title']}](site:apps/{name}.html): {application['description']}\n"
        site.page(f"apps/{name}.html", application["title"], application_page(site, application, require_artifacts),
                  site.source / "index.md")
    site.page("apps/index.html", "Application gallery", gallery, site.source / "index.md")
    validation = check_links(site.output)
    metadata = {"generator": GENERATOR, "source_files": site.sources,
                "calculation_catalog": catalog_metadata, "diagnostic_inventory": diagnostics,
                "public_declarations": declarations,
                "artifacts": site.artifacts, "artifact_issues": site.artifact_issues,
                "validation": validation, "required_artifacts": require_artifacts,
                "output_files": {path.relative_to(site.output).as_posix(): digest(path)
                                 for path in sorted(site.output.rglob("*")) if path.is_file()}}
    (site.output / "build-manifest.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
    return metadata


def previous_outputs(root, output):
    """Only replace this generator's unmodified outputs; preserve all other files."""
    if output.is_symlink():
        raise ValueError("Output directory must not be a symbolic link")
    output = output.resolve()
    root = root.resolve()
    if root.is_relative_to(output):
        raise ValueError("Output would overwrite the repository or its parent")
    if output.is_relative_to(root) and output.relative_to(root).parts[0] not in ("build", "out"):
        raise ValueError("In-repository output must be under build/ or out/, away from source files")
    if not output.exists():
        return {}
    if not output.is_dir():
        raise ValueError("Output must be a dedicated directory")
    entries = list(output.rglob("*"))
    if any(path.is_symlink() for path in entries):
        raise ValueError("Output directory contains symbolic links")
    existing = {path.relative_to(output).as_posix() for path in entries if path.is_file()}
    if not existing:
        return {}
    manifest_path = output / "build-manifest.json"
    if not manifest_path.is_file():
        raise ValueError("Nonempty output directory has no documentation build manifest")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("generator") != GENERATOR or not isinstance(manifest.get("output_files"), dict):
        raise ValueError("Output manifest is not owned by this generator")
    owned = manifest["output_files"]
    if existing - set(owned) - {"build-manifest.json"}:
        raise ValueError("Output contains files not owned by this generator")
    for name, expected in owned.items():
        path = (output / name).resolve()
        if not path.is_relative_to(output) or name == "build-manifest.json":
            raise ValueError("Output manifest contains an unconfined path")
        if path.exists() and (not path.is_file() or digest(path) != expected):
            raise ValueError(f"Generated output was modified; choose a new output directory: {name}")
    return owned


def generate(root, output, require_artifacts=False, catalog=None):
    output = output.absolute()
    old = previous_outputs(root, output)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Failed extraction, missing outputs or link validation cannot replace a previous valid site.
    with tempfile.TemporaryDirectory(prefix=".mantra-docs-", dir=output.parent) as temporary:
        staged = Path(temporary)
        metadata = generate_content(root, staged, require_artifacts, catalog)
        output.mkdir(exist_ok=True)
        for name in sorted(set(old) - set(metadata["output_files"])):
            path = output / name
            if path.exists():
                path.unlink()
        for name in sorted(metadata["output_files"]) + ["build-manifest.json"]:
            destination = output / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(staged / name, destination)
    return metadata


def main():
    sys.dont_write_bytecode = True
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path, default=ROOT / "build/docs-site")
    parser.add_argument("--require-artifacts", action="store_true", help="Fail without all showcase HTML/Text/XLSX outputs")
    parser.add_argument("--catalog", type=Path, help="Check names/summaries against actual mantra catalog output")
    parser.add_argument("--check-only", action="store_true", help="Validate an existing generated site's links")
    options = parser.parse_args()
    try:
        result = check_links(options.out) if options.check_only else generate(
            options.root, options.out, options.require_artifacts, options.catalog)
        print(json.dumps(result.get("validation", result), sort_keys=True))
    except (ValueError, OSError, KeyError, IndexError, zipfile.BadZipFile) as error:
        print(f"Documentation generation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
