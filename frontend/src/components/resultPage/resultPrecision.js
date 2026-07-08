export function applySignificantDigits(value, significantDigits) {
  const parsedSignificantDigits = Number(significantDigits);
  if (
    !Number.isInteger(parsedSignificantDigits) ||
    parsedSignificantDigits < 0
  ) {
    return value;
  }

  const valueStr = value.toString();
  if (!valueStr.includes(".")) {
    return value;
  }

  const decimalPlaces = valueStr.split(".")[1].length;
  if (decimalPlaces <= parsedSignificantDigits) {
    return value;
  }

  return parseFloat(value).toFixed(parsedSignificantDigits);
}
