// In DSH/univer_execute: paste this function, then await runPython({...}).
// Supply pythonExe from the current runtime when known. No fixed user/drive paths.
// File redirection avoids pipes on DSH environments that disallow them.
async function runPython(opts = {}) {
  const { spawn } = await import("node:child_process");
  const { openSync, closeSync, existsSync, mkdirSync } = await import("node:fs");
  const { readFile } = await import("node:fs/promises");
  const path = await import("node:path");
  const { homedir } = await import("node:os");
  const { script, args = [], pythonExe, cwd, timeoutMs = 120000 } = opts;
  if (!script) throw new Error("runPython: script is required");
  const outFile = opts.stdoutPath || script + ".out.txt";
  const errFile = outFile + ".stderr.txt";
  if (path.resolve(outFile) === path.resolve(script) || path.resolve(errFile) === path.resolve(script)) {
    throw new Error("runPython: log path must not overwrite script");
  }
  const candidates = [];
  function add(exe) {
    if (!exe) return;
    if (process.platform === "win32" && /python\.exe$/i.test(exe)) {
      candidates.push(exe.replace(/python\.exe$/i, "pythonw.exe"));
    }
    candidates.push(exe);
  }
  if (pythonExe) add(pythonExe);
  else {
    add(process.env.DSH_PYTHON);
    for (const folder of [
      path.join(homedir(), ".dsh", "dsh-runtimes", "dsh-primary-runtime", "dependencies", "python"),
      path.join(homedir(), ".cache", "codex-runtimes", "codex-primary-runtime", "dependencies", "python"),
      ...(process.env.PATH || "").split(path.delimiter).filter(Boolean)
    ]) add(path.join(folder, process.platform === "win32" ? "python.exe" : "python3"));
  }
  const exe = candidates.find(p => existsSync(p));
  if (!exe) throw new Error("runPython: interpreter unavailable; supply current runtime pythonExe once");
  mkdirSync(path.dirname(outFile), { recursive: true });
  const fhOut = openSync(outFile, "w");
  let fhErr;
  try { fhErr = openSync(errFile, "w"); }
  catch (error) { closeSync(fhOut); throw error; }
  const started = Date.now();
  let code, timedOut = false, error = null;
  try {
    const child = spawn(exe, ["-X", "utf8", script, ...args], {
      stdio: ["ignore", fhOut, fhErr], cwd, windowsHide: true,
      env: { ...process.env, PYTHONIOENCODING: "utf-8" }
    });
    code = await new Promise(resolve => {
      const timer = setTimeout(() => { timedOut = true; child.kill(); }, timeoutMs);
      child.once("error", e => { error = e.message; });
      child.once("close", c => { clearTimeout(timer); resolve(c); });
    });
  } catch (e) { error = e.message; }
  finally { closeSync(fhOut); closeSync(fhErr); }
  const stdout = await readFile(outFile, "utf8").catch(() => "");
  const stderr = await readFile(errFile, "utf8").catch(() => "");
  return { ok: code === 0 && !timedOut && !error, code, timedOut, error, exe,
    elapsedMs: Date.now() - started, stdout, stderr, stdoutPath: outFile, stderrPath: errFile };
}
