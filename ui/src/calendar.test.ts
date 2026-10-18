import { describe, expect, it } from "vitest"
import { daySlices, monthCells, openingMonth, type VersionSpan } from "./calendar"

function span(version: string, segments: VersionSpan["segments"], active = false): VersionSpan {
  return { version, versionNumber: Number(version.slice(1)), active, segments }
}

const oct1 = { effectiveFrom: "2026-09-30T18:30:00Z", effectiveUntil: "2026-10-01T18:29:00Z" }
const oct2 = { effectiveFrom: "2026-10-01T18:30:00Z", effectiveUntil: "2026-10-02T18:29:00Z" }
const oct3to4 = { effectiveFrom: "2026-10-02T18:30:00Z", effectiveUntil: "2026-10-04T18:29:00Z" }
const oct5 = { effectiveFrom: "2026-10-04T18:30:00Z", effectiveUntil: "2026-10-05T18:29:00Z" }
const oct6to10 = { effectiveFrom: "2026-10-05T18:30:00Z", effectiveUntil: "2026-10-10T18:29:00Z" }

describe("month grid", () => {
  it("starts Monday and pads October 2026", () => {
    const cells = monthCells(2026, 10)
    expect(cells.slice(0, 4)).toEqual([null, null, null, 1])
    expect(cells).toHaveLength(35)
    expect(cells.at(-1)).toBeNull()
  })
})

describe("day coverage", () => {
  const versions = [
    span("v2", [oct1, oct3to4, oct6to10]),
    span("v3", [oct2, oct5], true),
  ]

  it("paints a full day with the version that owns it", () => {
    expect(daySlices(versions, 2026, 10, 1).map((slice) => [slice.version, slice.fullDay])).toEqual([["v2", true]])
    expect(daySlices(versions, 2026, 10, 2).map((slice) => [slice.version, slice.fullDay])).toEqual([["v3", true]])
    expect(daySlices(versions, 2026, 10, 3).map((slice) => slice.version)).toEqual(["v2"])
    expect(daySlices(versions, 2026, 10, 5).map((slice) => slice.version)).toEqual(["v3"])
    expect(daySlices(versions, 2026, 10, 6).map((slice) => slice.version)).toEqual(["v2"])
    expect(daySlices(versions, 2026, 10, 11)).toEqual([])
  })

  it("shows the minutes when more than one version shares a day", () => {
    const shared = [
      span("v2", [{ effectiveFrom: "2026-09-30T18:30:00Z", effectiveUntil: "2026-10-01T04:29:00Z" }]),
      span("v3", [{ effectiveFrom: "2026-10-01T04:30:00Z", effectiveUntil: "2026-10-01T18:29:00Z" }]),
    ]
    const slices = daySlices(shared, 2026, 10, 1)
    expect(slices.map((slice) => [slice.version, slice.fullDay, slice.fromMinute, slice.untilMinute])).toEqual([
      ["v2", false, "00:00", "09:59"],
      ["v3", false, "10:00", "23:59"],
    ])
  })

  it("keeps an open end active through later months", () => {
    const open = [span("v1", [{ effectiveFrom: "2026-10-04T18:30:00Z", effectiveUntil: null }])]
    expect(daySlices(open, 2026, 10, 4)).toEqual([])
    expect(daySlices(open, 2026, 10, 5)[0]?.fullDay).toBe(true)
    expect(daySlices(open, 2027, 1, 1)[0]?.version).toBe("v1")
  })
})

describe("opening month", () => {
  it("opens on the focused version and skips a 1970 start", () => {
    const versions = [
      span("v1", [{ effectiveFrom: "1970-01-01T00:00:00Z", effectiveUntil: "2026-09-30T18:29:00Z" }]),
      span("v2", [{ effectiveFrom: "2026-09-30T18:30:00Z", effectiveUntil: "2026-10-10T18:29:00Z" }]),
    ]
    expect(openingMonth(versions, "v2")).toEqual({ year: 2026, month: 10 })
    expect(openingMonth(versions)).toEqual({ year: 2026, month: 10 })
  })
})
