import { applySignificantDigits } from "../resultPrecision";

describe("applySignificantDigits", () => {
  test("leaves raw analyzer precision untouched for the -1 sentinel", () => {
    expect(applySignificantDigits("45.678", -1)).toBe("45.678");
    expect(applySignificantDigits("45.678", "-1")).toBe("45.678");
  });

  test("preserves existing fixed-decimal rounding for non-negative precision", () => {
    expect(applySignificantDigits("45.678", 2)).toBe("45.68");
    expect(applySignificantDigits("45.678", "0")).toBe("46");
  });

  test("does not pad values that already fit the configured precision", () => {
    expect(applySignificantDigits("45.6", 2)).toBe("45.6");
  });
});
