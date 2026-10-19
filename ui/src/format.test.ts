import { describe, expect, it } from "vitest"
import {
  approvalMarks,
  approvalStageErrors,
  canonicalVersion,
  constructionDocument,
  findVersion,
  formatSchedule,
  formatWindow,
  isApprovedStage,
  isEditable,
  istInputValue,
  istOffset,
  roundUpMinute,
  runNote,
  runsNow,
  stageText,
  versionSummary,
} from "./format"
import type { RuleVersion } from "./model"

function version(token: string, active = false): RuleVersion {
  const number = Number(token.slice(1))
  return {
    id: number,
    versionNumber: number,
    version: token,
    description: null,
    effectiveFrom: "2026-01-01T00:00:00Z",
    effectiveUntil: "2026-12-31T23:59:59.999Z",
    active,
    definition: null,
    drl: null,
  }
}

describe("approval", () => {
  it("treats a missing stage as approved and a draft in the window as not running", () => {
    expect(isApprovedStage({})).toBe(true)
    expect(isApprovedStage({ stage: "APPROVED" })).toBe(true)
    expect(isApprovedStage({ stage: "DRAFT" })).toBe(false)
    expect(runsNow({ active: true, stage: "DRAFT" })).toBe(false)
    expect(runsNow({ active: true, stage: null })).toBe(true)
    expect(runsNow({ active: false, stage: "APPROVED" })).toBe(false)
    expect(isEditable({})).toBe(true)
    expect(isEditable({ stage: "DRAFT" })).toBe(true)
    expect(isEditable({ editable: false, stage: "DRAFT" })).toBe(false)
    expect(isEditable({ stage: "Checker" })).toBe(false)
    expect(stageText({ stage: "DRAFT" })).toBe("Draft")
    expect(stageText({ stageLabel: "Checker" })).toBe("Checker")
  })

  it("draws only the marks for the latest pass", () => {
    const latest = [
      { name: "DRAFT", label: "Draft", status: "current" as const },
      { name: "Checker", label: "Checker", status: "upcoming" as const },
      { name: "Sign-off", label: "Sign-off", status: "upcoming" as const },
      { name: "APPROVED", label: "Approved", status: "upcoming" as const },
    ]
    expect(approvalMarks({ stage: "DRAFT", stages: latest })).toEqual(latest)
    expect(approvalMarks({ stage: "APPROVED" })).toEqual([
      { name: "DRAFT", label: "Draft", status: "complete" },
      { name: "APPROVED", label: "Approved", status: "current" },
    ])
  })

  it("rejects a blank middle stage and a reserved name", () => {
    expect(approvalStageErrors(["  ", "Draft"])).toEqual([
      "Stage 1 needs a name.",
      "Stage 2 cannot be named Draft or Approved.",
    ])
    expect(approvalStageErrors(["Checker", "checker"])).toEqual(["Stage 2 repeats checker."])
  })

  it("describes a draft in the window as not running", () => {
    const draft = version("v1", true)
    draft.stage = "DRAFT"
    draft.stageLabel = "Draft"
    const approved = version("v2", false)
    approved.stage = "APPROVED"
    approved.stageLabel = "Approved"
    expect(versionSummary({ versions: [draft, approved] })).toBe(
      "2 versions · v1 is in Draft and will not run until it is approved",
    )
    approved.active = true
    draft.active = false
    expect(versionSummary({ versions: [draft, approved] })).toBe("2 versions · v2 running now")
    expect(runNote({ activeVersion: "v1", versions: [{ ...draft, active: true }, { ...approved, active: false }] })).toContain(
      "v1 is in Draft and will not run until it is approved",
    )
    expect(runNote({ activeVersion: "v2", versions: [{ ...draft, active: false }, { ...approved, active: true }] })).toContain(
      "uses v2",
    )
  })
})

describe("version tokens", () => {
  it("accepts v2, V2, and 2", () => {
    expect(canonicalVersion("v2")).toBe("v2")
    expect(canonicalVersion("V2")).toBe("v2")
    expect(canonicalVersion(" 2 ")).toBe("v2")
  })

  it("rejects a leading zero, zero, and other text", () => {
    expect(canonicalVersion("v0")).toBeNull()
    expect(canonicalVersion("v01")).toBeNull()
    expect(canonicalVersion("version2")).toBeNull()
    expect(canonicalVersion("")).toBeNull()
  })

  it("builds a request from the rule and the active version", () => {
    const later = version("v2")
    later.description = "Later"
    later.definition = { salience: 3, when: [], then: [] }
    expect(
      constructionDocument({
        id: 1,
        code: "senior",
        name: "Senior",
        activeVersion: "v2",
        versions: [version("v1", true), later],
      }),
    ).toEqual({
      code: "senior",
      name: "Senior",
      description: "Later",
      salience: 3,
      when: [],
      then: [],
    })
  })

  it("finds the version the token names", () => {
    const versions = [version("v1"), version("v2", true)]
    expect(findVersion(versions, "2")?.version).toBe("v2")
    expect(findVersion(versions, "v9")).toBeUndefined()
    expect(findVersion(versions, "v01")).toBeUndefined()
  })
})

function istLabel(iso: string): string {
  return `${new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Kolkata" }).format(new Date(iso))} IST`
}

describe("formatWindow", () => {
  it("shows India Standard Time, including the time", () => {
    const from = "2026-06-30T18:30:00Z"
    const until = "2026-07-31T18:29:59.999Z"
    expect(formatWindow(from, until)).toBe(`${istLabel(from)} – ${istLabel(until)}`)
    expect(formatWindow("2020-01-01T00:00:00Z", null)).toBe(`${istLabel("2020-01-01T00:00:00Z")} onward`)
    expect(formatWindow(null, null)).toBe("No effective dates")
  })

  it("joins every period when a version pauses and resumes", () => {
    const split = version("v2")
    split.segments = [
      { effectiveFrom: "2026-09-30T18:30:00Z", effectiveUntil: "2026-10-01T18:29:00Z" },
      { effectiveFrom: "2026-10-02T18:30:00Z", effectiveUntil: "2026-10-10T18:29:00Z" },
    ]
    const [first, second] = split.segments
    expect(formatSchedule(split)).toBe(
      `${formatWindow(first?.effectiveFrom, first?.effectiveUntil)} · ${formatWindow(second?.effectiveFrom, second?.effectiveUntil)}`,
    )
    expect(formatSchedule(version("v1"))).toBe(formatWindow("2026-01-01T00:00:00Z", "2026-12-31T23:59:59.999Z"))
  })
})

describe("India Standard Time input", () => {
  it("rounds a wall time up to the next minute and writes the +05:30 offset", () => {
    expect(istInputValue("2026-10-17T18:30:00Z")).toBe("2026-10-18T00:00:00")
    expect(roundUpMinute(new Date("2026-10-18T00:00:00.000Z")).toISOString()).toBe("2026-10-18T00:00:00.000Z")
    expect(roundUpMinute(new Date("2026-10-18T00:00:00.001Z")).toISOString()).toBe("2026-10-18T00:01:00.000Z")
    expect(istOffset("2026-10-18T00:00")).toBe("2026-10-18T00:00:00+05:30")
    expect(istOffset("2026-10-18T00:00:00")).toBe("2026-10-18T00:00:00+05:30")
  })
})
