/* eslint-env node */
module.exports = {
  root: true,
  env: { browser: true, es2021: true },
  extends: [
    'eslint:recommended',
  ],
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  settings: {
    react: { version: '18.3' },
  },
  ignorePatterns: ['dist', 'node_modules', '*.config.ts', '*.config.js'],
  rules: {
    'no-unused-vars': 'off',
    'no-undef': 'off',
  },
};
