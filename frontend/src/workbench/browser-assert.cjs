// Parinfer's disabled debug branch imports Node's assert. Keep a real assertion if that branch is ever enabled.
function assert(condition, message) {
  if (!condition) throw new Error(message || "Assertion failed");
}

module.exports = assert;
