import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { IntlProvider } from "react-intl";
import { vi } from "vitest";
import AnalyserResults from "./AnalyserResults";
import { ConfigurationContext, NotificationContext } from "../layout/Layout";
import messages from "../../languages/en.json";

// LIS-270: on wires that carry no patient identity (e.g. the SNIBE MAGLUMI X3,
// which sends a bare P|1 P-record), the LIS-239 same-day patient-mismatch guard
// is structurally inert — patientHint is null on every row, so a mis-keyed
// accession cannot be caught downstream. The staging worklist must therefore
// surface a prominent, worklist-level warning so the technician verifies each
// accession against the intended patient before accepting.
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

const WARNING_TITLE = "No patient identity from analyzer";

describe("AnalyserResults no-wire-patient-identity warning (LIS-270)", () => {
  it("shows the banner when a patient row carries no wire patient identity", () => {
    renderAnalyzerResults([
      {
        id: "r1",
        testName: "TSH",
        result: "2.1",
        completeDate: "2026-07-19",
        wirePatientIdentityAbsent: true,
      },
    ]);

    expect(screen.getByText(WARNING_TITLE)).toBeInTheDocument();
  });

  it("does not show the banner when every patient row has wire patient identity", () => {
    renderAnalyzerResults([
      {
        id: "r1",
        testName: "TSH",
        result: "2.1",
        completeDate: "2026-07-19",
        wirePatientIdentityAbsent: false,
      },
    ]);

    expect(screen.queryByText(WARNING_TITLE)).not.toBeInTheDocument();
  });

  it("ignores QC control rows — they carry no patient dimension", () => {
    renderAnalyzerResults([
      {
        id: "qc1",
        testName: "QC Level 1",
        result: "ok",
        completeDate: "2026-07-19",
        isControl: true,
        wirePatientIdentityAbsent: true,
      },
    ]);

    expect(screen.queryByText(WARNING_TITLE)).not.toBeInTheDocument();
  });
});
