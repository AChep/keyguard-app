// The error ending of the release scripts' CLIs: what failed on stderr, then
// exit 1, which fails the workflow's step.

/**
 * The message of [error], and that of its cause: a failed fetch() names the
 * network error in its cause, and a key that isn't PEM OpenSSL's error.
 */
export function errorText(error: unknown): string {
  if (!(error instanceof Error)) return String(error);
  return error.cause instanceof Error ? `${error.message}: ${error.cause.message}` : error.message;
}

/** Prints [error], and [hint] on its own line, to stderr, then exits with 1. */
export function fail(error: unknown, hint?: string): never {
  console.error([errorText(error), hint].filter(Boolean).join("\n"));
  process.exit(1);
}
