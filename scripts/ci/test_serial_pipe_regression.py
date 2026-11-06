"""CI-only offline Java regression; stubs simulate FD reuse, not Android/JNI."""
import os
from pathlib import Path
import subprocess
import tempfile

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Compile/run only in GitHub Actions; local static checks only")
root = Path(__file__).resolve().parents[2]
base = "d91ec3d485b1426bf92204037643940db088b3f0"
source = "app/src/main/java/cn/classfun/droidvm/daemon/vm/SerialPipe.java"
history = subprocess.run(["git", "show", f"{base}:{source}"], cwd=root, check=True, capture_output=True).stdout
stubs = sorted((root / "app/src/test/serialpipe-regression").rglob("*.java"))
helper = root / source.replace("SerialPipe.java", "SerialPipeFds.java")
with tempfile.TemporaryDirectory(prefix="serialpipe-regression-") as tmp:
    for label in ("historical", "candidate"):
        out = Path(tmp) / label
        out.mkdir()
        production = root / source
        if label == "historical":
            production = out / "SerialPipe.java"
            production.write_bytes(history)
        subprocess.run(["javac", "--release", "11", "-classpath", str(out), "-sourcepath", str(out), "-d", str(out), str(production), str(helper), *map(str, stubs)], check=True, timeout=60)
        run = subprocess.run(["java", "-cp", str(out), "cn.classfun.droidvm.daemon.vm.SerialPipeRegression"], capture_output=True, text=True, timeout=60)
        print(f"{label} ({base if label == 'historical' else 'working tree'}):\n{run.stdout}{run.stderr}", flush=True)
        if label == "historical":
            if run.returncode != 1 or "java.lang.AssertionError: raw-close adopted local" not in run.stderr or "ADOPTED_REUSE_GUARD_PASS" in run.stdout:
                raise SystemExit("Historical version did not reproduce the specific adopted/reused FD regression")
        elif run.returncode != 0 or "PASS production SerialPipe + helper" not in run.stdout:
            raise SystemExit("Candidate regression failed")
