import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import { vi } from "vitest";
import AnalyserResults from "./AnalyserResults";
import { ConfigurationContext, NotificationContext } from "../layout/Layout";
import messages from "../../languages/en.json";

const renderAnalyzerResults = (resultList) =>
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
            sampleGroup={[]}
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
});
