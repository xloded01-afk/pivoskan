"""Package tracked and non-ignored sources, excluding local SDK, caches and signing keys."""
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
files = subprocess.check_output(
    ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root
).decode("utf-8").split("\0")
destination = root / "dist/Pivoskan-source.zip"
destination.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name in sorted(set(files)):
        path = root / name
        if not name or not path.is_file():
            continue
        parts = Path(name).parts
        if any(part in {".git", ".gradle", ".kotlin", ".superpowers", "build", "dist"} for part in parts):
            continue
        if path.suffix.lower() in {".jks", ".keystore"} or path.name in {"local.properties", "keystore.properties"}:
            continue
        archive.write(path, "Pivoskan/" + name.replace("\\", "/"))

with zipfile.ZipFile(destination) as archive:
    assert archive.testzip() is None, "Corrupt archive"
    names = set(archive.namelist())
    for required in ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "settings.gradle.kts",
                     "app/build.gradle.kts", "app/src/main/AndroidManifest.xml", "README.md", "build.ps1"]:
        assert "Pivoskan/" + required in names, f"Missing source file: {required}"
    assert not any(name.endswith((".jks", "/keystore.properties", "/local.properties")) for name in names)
print(f"Source archive verified: {len(names)} files, {destination.stat().st_size:,} bytes")
