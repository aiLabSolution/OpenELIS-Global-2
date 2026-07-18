import React from "react";
import { render, screen, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import { vi } from "vitest";
import AnalyserResults from "./AnalyserResults";
import { ConfigurationContext, NotificationContext } from "../layout/Layout";
import messages from "../../languages/en.json";

const renderAnalyzerResults = (resultList, sampleGroup = []) =>
  render(
    <IntlProvider locale="en" messages={messages}>
      <ConfigurationContext.Provider
        value={{ configurationProperties: { AccessionFormat: "" } }}
      >
        <NotificationContext.Provider
          value={{
            setNotificationVisible: vi.fn(),
            addNotification: vi.fn(),
          }}
        >
          <AnalyserResults
            results={{ resultList }}
            sampleGroup={sampleGroup}
            queryMode="id"
            queryValue="22"
          />
        </NotificationContext.Provider>
      </ConfigurationContext.Provider>
    </IntlProvider>,
  );

describe("AnalyserResults test-name provenance", () => {
  it("shows the raw analyzer code and unit beside their normalized values", () => {
    renderAnalyzerResults([
      {
        id: "result-1",
        testName: "White Blood Cell Count",
        rawCode: "WBC",
        rawUnit: "10^9/L",
        loinc: "6690-2",
        ucumValue: "10*9/L",
        normalizationStatus: "NORMALIZED",
        result: "5.2",
        completeDate: "2026-07-16",
        readOnly: true,
      },
    ]);

    expect(screen.getByText("White Blood Cell Count")).toBeInTheDocument();
    expect(screen.getByText("Raw code WBC → LOINC 6690-2")).toBeInTheDocument();
    expect(
      screen.getByText("Raw unit 10^9/L → UCUM 10*9/L"),
    ).toBeInTheDocument();
    expect(screen.getByText("Normalization NORMALIZED")).toBeInTheDocument();
  });

  it("keeps the original test-name cell for rows without provenance", () => {
    renderAnalyzerResults([
      {
        id: "legacy-result",
        testName: "Glucose",
        result: "92",
        completeDate: "2026-07-16",
        readOnly: true,
      },
    ]);

    expect(screen.getByTestId("sampleInfo")).toHaveTextContent(/^Glucose$/);
    expect(screen.queryByText(/Raw code/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Raw unit/)).not.toBeInTheDocument();
  });

  it("shows the complete analyzer range and normal flag for a mapped result", () => {
    const longReferenceRange =
      "3.5000000000000000001-10.5000000000000000009 x 10^9/L; analyzer comment: fasting specimen; suffix-preserved";

    renderAnalyzerResults(
      [
        {
          id: "1",
          testName: "White Blood Cell Count",
          result: "5.2",
          referenceRange: longReferenceRange,
          abnormalFlag: "N",
          completeDate: "2026-07-16",
          readOnly: false,
        },
      ],
      [{ id: "1" }],
    );

    const rangeCell = screen.getByTestId("analyzerRange");
    expect(within(rangeCell).getByText(longReferenceRange)).toBeVisible();
    expect(within(rangeCell).getByText("N").closest(".cds--tag")).toHaveClass(
      "cds--tag--green",
    );
    expect(screen.getByDisplayValue("5.2")).toBeInTheDocument();
  });

  it("shows abnormal and textual flags for unmapped read-only results", () => {
    renderAnalyzerResults([
      {
        id: "2",
        testName: "Hemoglobin",
        result: "18.4",
        referenceRange: "12.0-16.0 g/dL",
        abnormalFlag: "H",
        completeDate: "2026-07-16",
        readOnly: true,
      },
      {
        id: "3",
        testName: "Platelet Count",
        result: "88",
        referenceRange: "150-450 x 10^9/L",
        abnormalFlag: "L",
        completeDate: "2026-07-16",
        readOnly: true,
      },
      {
        id: "4",
        testName: "Analyzer Review Signal",
        result: "review required",
        abnormalFlag: "SUSP",
        completeDate: "2026-07-16",
        readOnly: true,
      },
    ]);

    const [highCell, lowCell, textualCell] =
      screen.getAllByTestId("analyzerRange");
    expect(within(highCell).getByText("H").closest(".cds--tag")).toHaveClass(
      "cds--tag--red",
    );
    expect(within(lowCell).getByText("L").closest(".cds--tag")).toHaveClass(
      "cds--tag--red",
    );
    const textualFlag = within(textualCell).getByText("SUSP");
    expect(textualFlag).toBeVisible();
    expect(textualFlag.closest(".cds--tag")).toHaveClass("cds--tag--red");
    expect(screen.getByText("18.4")).toBeVisible();
    expect(screen.getByText("88")).toBeVisible();
    expect(screen.getByText("review required")).toBeVisible();
  });
});
