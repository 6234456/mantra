#!/usr/bin/env python3
"""Inspect local files only. This neither signs artifacts nor uploads/releases anything."""
from pathlib import Path
from zipfile import ZipFile
import argparse
import xml.etree.ElementTree as ET

MODULES = ["mantra-core", "mantra-render", "mantra-excel", "mantra-workbench", "mantra-server", "mantra-packages"]
EXPECTED_COMPILE = {
    "mantra-core": ["normein-dsl", "kotlin-stdlib"],
    "mantra-render": ["mantra-core", "kotlin-stdlib"],
    "mantra-excel": ["mantra-render", "poi-ooxml", "kotlin-stdlib"],
    "mantra-workbench": ["mantra-core", "mantra-render", "mantra-packages", "kotlin-stdlib"],
    "mantra-server": ["mantra-workbench", "kotlin-stdlib"],
    "mantra-packages": ["mantra-core", "kotlin-stdlib"],
}


def verify(repository: Path, version: str) -> None:
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    for module in MODULES:
        directory = repository / "com/xqiou/mantra" / module / version
        # SNAPSHOT Maven repositories can timestamp the actual files; do not assume unsuffixed names.
        poms = sorted(directory.glob("*.pom"))
        assert poms, f"{module}: local POM missing"
        pom = ET.parse(poms[-1]).getroot()
        dependencies = {
            item.findtext("m:artifactId", namespaces=namespace): item.findtext("m:scope", default="compile", namespaces=namespace)
            for item in pom.findall("m:dependencies/m:dependency", namespace)
        }
        for artifact in EXPECTED_COMPILE[module]:
            assert dependencies.get(artifact) == "compile", f"{module}: {artifact} must be a compile dependency, found {dependencies.get(artifact)}"
        for suffix in ["", "-sources", "-javadoc"]:
            jars = [path for path in directory.glob("*.jar") if (path.stem.endswith(suffix) if suffix else not path.stem.endswith(("-sources", "-javadoc")))]
            assert jars, f"{module}: {suffix or 'binary'} jar missing"
            with ZipFile(jars[-1]) as archive:
                names = archive.namelist()
                if suffix == "":
                    assert any(name.endswith(".class") for name in names), f"{module}: empty binary"
                elif suffix == "-sources":
                    assert any(name.endswith((".kt", ".java")) for name in names), f"{module}: source archive empty"
                else:
                    assert any(name.endswith("index.html") for name in names), f"{module}: Kotlin API HTML missing from documentation archive"
        assert pom.findtext("m:licenses/m:license/m:name", namespaces=namespace), f"{module}: license metadata missing"
        assert pom.findtext("m:developers/m:developer/m:name", namespaces=namespace), f"{module}: developer name missing"
        assert pom.findtext("m:developers/m:developer/m:url", namespaces=namespace), f"{module}: developer URL missing"
    artifacts = {path.name for path in (repository / "com/xqiou/mantra").iterdir() if path.is_dir()}
    assert artifacts == set(MODULES), f"Only library modules may be staged, found {sorted(artifacts)}"
    print("Local binary/source/API-HTML artifacts and compile scopes verified; no remote publication claimed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    verify(args.repository, args.version)
