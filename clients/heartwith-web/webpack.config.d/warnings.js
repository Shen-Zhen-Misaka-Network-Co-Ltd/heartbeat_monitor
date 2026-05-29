config.ignoreWarnings = [
  (warning) => warning.message === "Critical dependency: the request of a dependency is an expression",
  (warning) => warning.message === "Critical dependency: Accessing import.meta directly is unsupported (only property access or destructuring is supported)",
];

config.performance = {
  hints: false,
};
