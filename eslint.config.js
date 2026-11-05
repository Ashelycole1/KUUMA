// @ts-check
import js from "@eslint/js";
import tseslint from "typescript-eslint";

export default tseslint.config(
  {
    ignores: [
      "**/node_modules/**",
      "**/dist/**",
      "**/build/**",
      "**/.dart_tool/**",
      "packages/contracts/generated/**",
      "apps/**",
      "modules/**/android/**",
      "modules/**/flutter/**",
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.ts"],
    rules: {
      // Rule 7: never log message content. Use structured, content-free logging.
      "no-console": ["error", { allow: ["error"] }],
    },
  },
);
