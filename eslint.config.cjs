const ionic = require('@ionic/eslint-config/recommended');

module.exports = [
  {
    ignores: [
      'build/**',
      'dist/**',
      'example-app/**',
      'example-backend/**',
      'example-web-app/**',
      'docs/**',
    ],
  },
  ...ionic,
];
