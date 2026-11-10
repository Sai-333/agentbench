// Default AgentBench eslint flat config for quality-stage scans when a workspace
// has no project eslint config. Keep rules small and deterministic.
export default [
  {
    files: ["**/*.{js,mjs,cjs,jsx,ts,tsx}"],
    ignores: ["node_modules/**", "dist/**", "build/**", "coverage/**", ".next/**"],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "module",
    },
    rules: {
      "no-console": "warn",
      "no-eval": "error",
      "no-var": "warn",
      "eqeqeq": ["warn", "always"],
      "no-unused-vars": "warn",
      "no-undef": "off",
    },
  },
];
