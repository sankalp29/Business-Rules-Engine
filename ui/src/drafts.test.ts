import { describe, expect, it } from "vitest"
import {
  buildRule,
  dateLocks,
  draftFromFields,
  draftFromVersion,
  editEffectiveDates,
  editEffectiveSegments,
  emptyComparison,
  emptyRule,
  executePayload,
  fieldToJson,
  incompleteNumbers,
  oneDayPeriod,
  parseNumber,
  sanitizeNumber,
  shiftYears,
  suggestNextWindow,
  type AssignmentDraft,
  type ComparisonDraft,
  type FactDraft,
  type FieldDraft,
  type RuleDraft,
  type WhenDraft,
} from "./drafts"
import { istInputValue } from "./format"
import type { RuleVersion } from "./model"
import type { Leaf } from "./model"

const customerLeaves: Leaf[] = [
  { path: "name", type: "String" },
  { path: "age", type: "int" },
  { path: "discount", type: "double" },
  { path: "segment", type: "String" },
  { path: "approved", type: "boolean" },
  { path: "nickname", type: "String" },
]

function comparison(patch: Partial<ComparisonDraft>): ComparisonDraft {
  return {
    id: "c",
    kind: "comparison",
    field: "age",
    operator: ">=",
    rhs: "value",
    valueText: "65",
    valueBool: false,
    valueNull: false,
    valueField: "",
    ...patch,
  }
}

function assignment(patch: Partial<AssignmentDraft>): AssignmentDraft {
  return {
    id: "a",
    field: "discount",
    mode: "value",
    valueText: "0.2",
    valueBool: false,
    valueNull: false,
    expression: "",
    ...patch,
  }
}

function when(root: ComparisonDraft | WhenDraft["root"] = comparison({})): WhenDraft {
  return { id: "w", binding: "$customer", bindingTouched: true, type: "Customer", root }
}

function rule(patch: Partial<RuleDraft> = {}): RuleDraft {
  return {
    code: "senior-discount",
    name: "Apply senior discount",
    description: "Customers aged 65 or older receive a senior discount",
    salience: "",
    effectiveFrom: "2090-01-01T00:00",
    effectiveUntil: "2099-12-31T00:00",
    whens: [when()],
    thens: [
      {
        id: "t",
        binding: "$customer",
        assignments: [
          assignment({ field: "discount", valueText: "0.2" }),
          assignment({ id: "a2", field: "segment", valueText: "SENIOR" }),
        ],
      },
    ],
    ...patch,
  }
}

describe("typed values", () => {
  it("keeps booleans boolean, numbers numeric, and text as text", () => {
    const values = draftFromFields([
      { name: "approved", type: "boolean" },
      { name: "age", type: "int" },
      { name: "discount", type: "double" },
      { name: "name", type: "String" },
      { name: "address", type: "Address", fields: [{ name: "city", type: "String" }, { name: "zip", type: "String" }] },
    ])
    values.approved = { kind: "boolean", value: false }
    values.age = { kind: "number", text: "70", integer: true }
    values.discount = { kind: "number", text: "0.2", integer: false }
    values.name = { kind: "string", text: "Ada" }
    values.address = {
      kind: "object",
      children: {
        city: { kind: "string", text: "Pune" },
        zip: { kind: "string", text: "" },
      },
    }

    const fact: FactDraft = { id: "f", type: "Customer", fields: [], values }
    expect(executePayload([fact])).toEqual({
      facts: [
        {
          type: "Customer",
          values: {
            approved: false,
            age: 70,
            discount: 0.2,
            name: "Ada",
            address: { city: "Pune" },
          },
        },
      ],
    })
    const sent = executePayload([fact]).facts[0].values
    expect(typeof sent.approved).toBe("boolean")
    expect(typeof sent.age).toBe("number")
    expect(typeof sent.discount).toBe("number")
    expect(typeof sent.name).toBe("string")
  })

  it("sends a true toggle as a JSON boolean and omits a blank number", () => {
    const values = draftFromFields([
      { name: "approved", type: "Boolean" },
      { name: "age", type: "Integer" },
    ])
    values.approved = { kind: "boolean", value: true }
    expect(executePayload([{ id: "f", type: "Order", fields: [], values }]).facts[0].values).toEqual({
      approved: true,
    })
  })

  it("strips letters from number entry and rejects an unfinished number", () => {
    expect(sanitizeNumber("12e4 years", true)).toBe("124")
    expect(sanitizeNumber("-0.20.1", false)).toBe("-0.201")
    expect(sanitizeNumber("abc", false)).toBe("")
    expect(parseNumber("65", true)).toBe(65)
    expect(parseNumber("12.5", true)).toBeUndefined()
    expect(parseNumber("0.2", false)).toBe(0.2)
    const values = draftFromFields([{ name: "age", type: "int" }])
    values.age = { kind: "number", text: "-", integer: true }
    expect(incompleteNumbers(values, "Customer")).toEqual(["Customer.age needs a whole number."])
  })
})

describe("rule construction", () => {
  it("builds a senior discount with numeric and string values", () => {
    const built = buildRule(rule(), [customerLeaves])
    expect(built.errors).toEqual([])
    expect(built.body).toEqual({
      code: "senior-discount",
      name: "Apply senior discount",
      description: "Customers aged 65 or older receive a senior discount",
      effectiveFrom: "2090-01-01T00:00:00+05:30",
      effectiveUntil: "2099-12-31T00:00:00+05:30",
      when: [
        {
          binding: "$customer",
          type: "Customer",
          conditions: { field: "age", operator: ">=", value: 65 },
        },
      ],
      then: [
        {
          binding: "$customer",
          assignments: [
            { field: "discount", value: 0.2 },
            { field: "segment", value: "SENIOR" },
          ],
        },
      ],
    })
    const then = built.body?.then as { assignments: { value: unknown }[] }[]
    expect(typeof then[0].assignments[0].value).toBe("number")
    expect(typeof then[0].assignments[1].value).toBe("string")
  })

  it("emits a boolean assignment as a boolean and an expression without a value", () => {
    const built = buildRule(
      rule({
        thens: [
          {
            id: "t",
            binding: "$customer",
            assignments: [
              assignment({ field: "approved", valueBool: true }),
              assignment({ id: "e", field: "discount", mode: "expression", expression: "$customer.getAge() * 0.01" }),
            ],
          },
        ],
      }),
      [customerLeaves],
    )
    expect(built.errors).toEqual([])
    expect(built.body?.then).toEqual([
      {
        binding: "$customer",
        assignments: [
          { field: "approved", value: true },
          { field: "discount", expression: "$customer.getAge() * 0.01" },
        ],
      },
    ])
  })

  it("nests and/or groups and compares another field", () => {
    const built = buildRule(
      rule({
        salience: "10",
        whens: [
          when({
            id: "g",
            kind: "group",
            operator: "or",
            children: [
              {
                id: "and",
                kind: "group",
                operator: "and",
                children: [
                  comparison({ field: "age", operator: ">=", valueText: "65" }),
                  comparison({ id: "seg", field: "segment", operator: "==", valueText: "GOLD" }),
                ],
              },
              comparison({ id: "name", field: "name", operator: "==", rhs: "field", valueField: "nickname" }),
            ],
          }),
        ],
      }),
      [customerLeaves],
    )
    expect(built.errors).toEqual([])
    expect(built.body?.salience).toBe(10)
    expect(built.body?.when).toEqual([
      {
        binding: "$customer",
        type: "Customer",
        conditions: {
          operator: "or",
          conditions: [
            {
              operator: "and",
              conditions: [
                { field: "age", operator: ">=", value: 65 },
                { field: "segment", operator: "==", value: "GOLD" },
              ],
            },
            { field: "name", operator: "==", valueField: "nickname" },
          ],
        },
      },
    ])
  })

  it("refuses a window that ends before it starts", () => {
    const built = buildRule(rule({ effectiveFrom: "2090-12-31T00:00", effectiveUntil: "2090-01-01T00:00" }), [customerLeaves])
    expect(built.body).toBeNull()
    expect(built.errors).toContain("The end must be later than the start.")
  })

  it("refuses a start that is already in the past", () => {
    const built = buildRule(rule({ effectiveFrom: "2020-01-01T00:00", effectiveUntil: "2099-12-31T00:00" }), [customerLeaves])
    expect(built.body).toBeNull()
    expect(built.errors).toContain("The start cannot be before the current time.")
  })

  it("allows the current minute, refuses seconds, and sends every period", () => {
    const now = new Date("2026-10-18T00:00:30.000Z")
    const current = buildRule(
      rule({ effectiveFrom: "2026-10-18T05:30", effectiveUntil: "2090-01-01T00:00" }),
      [customerLeaves],
      now,
    )
    expect(current.errors).toEqual([])
    expect(current.body?.segments).toBeUndefined()

    const seconds = buildRule(
      rule({ effectiveFrom: "2090-01-01T00:00:30", effectiveUntil: "2090-01-02T00:00:01" }),
      [customerLeaves],
      now,
    )
    expect(seconds.body).toBeNull()
    expect(seconds.errors).toContain("Use a whole minute for the start.")
    expect(seconds.errors).toContain("Use a whole minute for the end.")

    const ended = buildRule(
      rule({ effectiveFrom: "2026-10-18T05:30", effectiveUntil: "2026-10-18T05:29" }),
      [customerLeaves],
      new Date("2026-10-18T00:00:00.000Z"),
    )
    expect(ended.errors).toContain("The end cannot be before the current time.")

    const split = buildRule(
      rule({
        effectiveFrom: "2090-11-01T00:00",
        effectiveUntil: "2090-11-01T23:59",
        extraSegments: [{ effectiveFrom: "2090-11-03T00:00", effectiveUntil: "2090-11-10T23:59" }],
      }),
      [customerLeaves],
      now,
    )
    expect(split.errors).toEqual([])
    expect(split.body?.effectiveFrom).toBe("2090-11-01T00:00:00+05:30")
    expect(split.body?.effectiveUntil).toBe("2090-11-01T23:59:00+05:30")
    expect(split.body?.segments).toEqual([
      { effectiveFrom: "2090-11-01T00:00:00+05:30", effectiveUntil: "2090-11-01T23:59:00+05:30" },
      { effectiveFrom: "2090-11-03T00:00:00+05:30", effectiveUntil: "2090-11-10T23:59:00+05:30" },
    ])

    const overlap = buildRule(
      rule({
        effectiveFrom: "2090-11-01T00:00",
        effectiveUntil: "2090-11-10T23:59",
        extraSegments: [{ effectiveFrom: "2090-11-02T00:00", effectiveUntil: "2090-11-02T23:59" }],
      }),
      [customerLeaves],
      now,
    )
    expect(overlap.body).toBeNull()
    expect(overlap.errors).toContain("Periods overlap each other.")
  })

  it("defaults a new rule to the next India Standard Time minute", () => {
    const now = new Date("2026-10-18T00:00:30.000Z")
    const draft = emptyRule(now)
    expect(draft.effectiveFrom).toBe("2026-10-18T05:31:00")
    expect(draft.effectiveUntil).toBe(shiftYears(draft.effectiveFrom, 1))
  })

  it("copies a version's logic and suggests the following window", () => {
    const version: RuleVersion = {
      id: 2,
      versionNumber: 1,
      version: "v1",
      description: "First cut",
      effectiveFrom: "2090-01-01T00:00:00+05:30",
      effectiveUntil: "2090-06-30T18:29:59.999Z",
      active: false,
      definition: {
        salience: 4,
        when: [
          {
            binding: "$customer",
            type: "Customer",
            conditions: {
              operator: "or",
              conditions: [
                { field: "age", operator: ">=", value: 65 },
                { field: "segment", operator: "==", value: null },
              ],
            },
          },
        ],
        then: [{ binding: "$customer", assignments: [{ field: "discount", expression: "$customer.getAge() * 0.01" }] }],
      },
      drl: null,
    }
    const draft = draftFromVersion({ code: "senior-discount", name: "Apply senior discount" }, version, suggestNextWindow(version.effectiveUntil))
    expect(draft.code).toBe("senior-discount")
    expect(draft.name).toBe("Apply senior discount")
    expect(draft.description).toBe("First cut")
    expect(draft.salience).toBe("4")
    expect(draft.effectiveFrom).toBe("2090-07-01T00:00:00")
    expect(draft.effectiveUntil).toBe("2091-07-01T00:00:00")
    expect(draft.whens[0]?.root).toMatchObject({
      kind: "group",
      operator: "or",
    })
    const built = buildRule(draft, [customerLeaves])
    expect(built.errors).toEqual([])
    expect(built.body?.then).toEqual([
      { binding: "$customer", assignments: [{ field: "discount", expression: "$customer.getAge() * 0.01" }] },
    ])
  })

  it("refuses a fractional value for an integer field", () => {
    const built = buildRule(rule({ whens: [when(comparison({ valueText: "65.5" }))] }), [customerLeaves])
    expect(built.body).toBeNull()
    expect(built.errors.some((error) => error.includes("whole number"))).toBe(true)
  })
})

describe("effective dates", () => {
  const now = new Date("2026-10-18T00:00:00.000Z")

  it("keeps a start that is already in the past and moves the end", () => {
    const edited = editEffectiveDates({
      fromInput: "2026-10-19T00:00:00",
      untilInput: "2090-06-01T00:00",
      storedFrom: "1970-01-01T00:00:00Z",
      storedUntil: "2090-01-01T18:29:59.999Z",
      now,
    })
    expect(edited.errors).toEqual(["effectiveFrom cannot change because it is already before the current time"])
    expect(edited.body).toBeNull()
  })

  it("sends the stored instant when a locked or untouched field is submitted again", () => {
    const storedFrom = "2090-01-01T00:00:00.999Z"
    const storedUntil = "2091-01-01T18:29:59.999Z"
    const edited = editEffectiveDates({
      fromInput: istInputValue(storedFrom),
      untilInput: "2092-01-01T00:00",
      storedFrom,
      storedUntil,
      now,
    })
    expect(edited.errors).toEqual([])
    expect(edited.body).toEqual({
      effectiveFrom: storedFrom,
      effectiveUntil: "2092-01-01T00:00:00+05:30",
    })
  })

  it("rejects a new start that is before now and an end that is not later", () => {
    const edited = editEffectiveDates({
      fromInput: "2020-01-01T00:00",
      untilInput: "2020-01-01T00:00",
      storedFrom: "2090-01-01T00:00:00+05:30",
      storedUntil: "2091-01-01T00:00:00+05:30",
      now,
    })
    expect(edited.body).toBeNull()
    expect(edited.errors).toContain("The start cannot be before the current time.")
    expect(edited.errors).toContain("The end must be later than the start.")
  })

  it("asks for an end when a legacy version is still open", () => {
    const edited = editEffectiveDates({
      fromInput: istInputValue("1970-01-01T00:00:00Z"),
      untilInput: "",
      storedFrom: "1970-01-01T00:00:00Z",
      storedUntil: null,
      now,
    })
    expect(edited.body).toBeNull()
    expect(edited.errors).toEqual(["Enter the time this version ends."])
  })

  it("refuses an end that is before the current minute", () => {
    const edited = editEffectiveDates({
      fromInput: istInputValue("1970-01-01T00:00:00Z"),
      untilInput: "2026-10-18T05:29",
      storedFrom: "1970-01-01T00:00:00Z",
      storedUntil: "2090-01-01T18:29:00Z",
      now,
    })
    expect(edited.body).toBeNull()
    expect(edited.errors).toEqual(["The end cannot be before the current time."])
  })

  it("refuses a new bound that is finer than a minute and allows the current minute", () => {
    const seconds = editEffectiveDates({
      fromInput: "2090-01-01T00:00:30",
      untilInput: "2091-01-01T00:00:00",
      storedFrom: "2090-06-01T00:00:00+05:30",
      storedUntil: "2091-01-01T00:00:00+05:30",
      now,
    })
    expect(seconds.body).toBeNull()
    expect(seconds.errors).toContain("Use a whole minute for the start.")

    const currentMinute = new Date("2026-10-18T00:00:30.000Z")
    const edited = editEffectiveDates({
      fromInput: "2026-10-18T05:30",
      untilInput: "2090-01-01T00:00",
      storedFrom: "2090-06-01T00:00:00+05:30",
      storedUntil: "2091-01-01T00:00:00+05:30",
      now: currentMinute,
    })
    expect(edited.errors).toEqual([])
    expect(edited.body?.effectiveFrom).toBe("2026-10-18T05:30:00+05:30")
    expect(dateLocks("2026-10-18T00:00:00.000Z", "2026-10-18T00:00:10.000Z", currentMinute)).toEqual({
      fromLocked: false,
      untilLocked: false,
    })
  })

  it("sends several periods and keeps one period as a single pair", () => {
    const one = editEffectiveSegments({
      segments: [
        {
          fromInput: "2090-01-01T00:00",
          untilInput: "2090-01-02T00:00",
          storedFrom: "2090-01-01T00:00:00+05:30",
          storedUntil: "2090-06-01T00:00:00+05:30",
        },
      ],
      now,
    })
    expect(one.errors).toEqual([])
    expect(one.body?.segments).toBeUndefined()

    const two = editEffectiveSegments({
      segments: [
        {
          fromInput: istInputValue("1970-01-01T00:00:00Z"),
          untilInput: istInputValue("2026-10-01T18:29:00Z"),
          storedFrom: "1970-01-01T00:00:00Z",
          storedUntil: "2026-10-01T18:29:00Z",
        },
        {
          fromInput: "2090-11-03T00:00",
          untilInput: "2090-11-10T23:59",
          storedFrom: null,
          storedUntil: null,
        },
      ],
      now,
    })
    expect(two.errors).toEqual([])
    expect(two.body?.segments).toEqual([
      { effectiveFrom: "1970-01-01T00:00:00Z", effectiveUntil: "2026-10-01T18:29:00Z" },
      { effectiveFrom: "2090-11-03T00:00:00+05:30", effectiveUntil: "2090-11-10T23:59:00+05:30" },
    ])
  })

  it("uses today for a one-day period at midnight and the next day after that", () => {
    expect(oneDayPeriod(new Date("2026-10-17T18:30:00.000Z"))).toEqual({
      effectiveFrom: "2026-10-18T00:00:00",
      effectiveUntil: "2026-10-18T23:59:00",
    })
    expect(oneDayPeriod(new Date("2026-10-18T00:00:00.000Z"))).toEqual({
      effectiveFrom: "2026-10-19T00:00:00",
      effectiveUntil: "2026-10-19T23:59:00",
    })
  })

  it("suggests the next India Standard Time day, or now when that day has passed", () => {
    expect(suggestNextWindow("2090-06-30T18:29:59.999Z", now)).toEqual({
      effectiveFrom: "2090-07-01T00:00:00",
      effectiveUntil: "2091-07-01T00:00:00",
    })
    expect(suggestNextWindow(null, now)).toEqual({
      effectiveFrom: "2026-10-18T05:30:00",
      effectiveUntil: "2027-10-18T05:30:00",
    })
    expect(suggestNextWindow("not-a-date", now)).toEqual({ effectiveFrom: "", effectiveUntil: "" })
    expect(suggestNextWindow("2101-01-01T00:00:00+05:30", now)).toEqual({ effectiveFrom: "", effectiveUntil: "" })
  })
})

describe("field json", () => {
  it("sends either a type or nested fields", () => {
    const scalar: FieldDraft = {
      id: "1",
      name: " age ",
      mode: "scalar",
      scalar: "int",
      typeName: "",
      children: [],
    }
    const nested: FieldDraft = {
      id: "2",
      name: "address",
      mode: "object",
      scalar: "String",
      typeName: "",
      children: [{ ...scalar, id: "3", name: "city", scalar: "String" }],
    }
    const reference: FieldDraft = {
      id: "4",
      name: "home",
      mode: "type",
      scalar: "String",
      typeName: "Address",
      children: [],
    }
    expect(fieldToJson(scalar)).toEqual({ name: "age", type: "int" })
    expect(fieldToJson(nested)).toEqual({ name: "address", fields: [{ name: "city", type: "String" }] })
    expect(fieldToJson(reference)).toEqual({ name: "home", type: "Address" })
    expect(emptyComparison().kind).toBe("comparison")
  })
})
