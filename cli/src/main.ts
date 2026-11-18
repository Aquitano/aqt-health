#!/usr/bin/env bun
import { run } from "./cli";

process.exitCode = await run(process.argv.slice(2), process.env, {
  out: (text) => process.stdout.write(text),
  err: (text) => process.stderr.write(text),
});
