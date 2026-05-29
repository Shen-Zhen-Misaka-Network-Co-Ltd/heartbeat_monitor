config.ignoreWarnings = [
  (warning) => warning.message === "Critical dependency: the request of a dependency is an expression",
  (warning) => warning.message === "Critical dependency: Accessing import.meta directly is unsupported (only property access or destructuring is supported)",
  (warning) => warning.message?.includes("Failed to parse source map from")
    && warning.message?.includes("kotlin-web-helpers"),
];

config.performance = {
  hints: false,
};
