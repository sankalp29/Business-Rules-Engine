import { describe, expect, it } from "vitest"
import {
  buildLogic,
  buildRule,
  changeFactType,
  dateLocks,
  draftFromFields,
  draftFromVersion,
  editEffectiveDates,
  editEffectiveSegments,
  overlappingWindows,
  emptyComparison,
  emptyRule,
  executePayload,
  valueFieldOptions,
  fieldToJson,
  incompleteNumbers,
  oneDayPeriod,
  parseNumber,
  addBranch,
  chainLeaves,
  removeBranch,
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

const orderLeaves: Leaf[] = [
  { path: "total", type: "double" },
  { path: "priority", type: "String" },
]

function versionWith(definition: RuleVersion["definition"]): RuleVersion {
  return {
    id: 3,
    versionNumber: 1,
    version: "v1",
    description: "Tiered",
    effectiveFrom: "2090-01-01T00:00:00+05:30",
    effectiveUntil: "2091-01-01T00:00:00+05:30",
    active: false,
    definition,
    drl: null,
  }
}

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
  return { id: "w", binding: "$customer", type: "Customer", root }
}

function segmentChain() {
  return {
    id: "tier",
    ifArm: {
      id: "senior",
      when: comparison({ field: "age", operator: ">=", valueText: "65" }),
      assignments: [assignment({ id: "senior", field: "segment", valueText: "SENIOR" })],
    },
    elseIf: [
      {
        id: "adult",
        when: comparison({ id: "adult", field: "age", operator: ">=", valueText: "40" }),
        assignments: [assignment({ id: "adult", field: "segment", valueText: "ADULT" })],
      },
    ],
    otherwise: {
      id: "young",
      assignments: [assignment({ id: "young", field: "segment", valueText: "YOUNG" })],
    },
  }
}

function discountChain() {
  return {
    id: "price",
    ifArm: {
      id: "gold",
      when: comparison({ id: "gold", field: "segment", operator: "==", valueText: "GOLD" }),
      assignments: [assignment({ id: "gold", field: "discount", valueText: "0.2" })],
    },
    elseIf: [
      {
        id: "silver",
        when: comparison({ id: "silver", field: "segment", operator: "==", valueText: "SILVER" }),
        assignments: [assignment({ id: "silver", field: "discount", valueText: "0.1" })],
      },
    ],
    otherwise: {
      id: "standard",
      assignments: [assignment({ id: "standard", field: "discount", valueText: "0.05" })],
    },
  }
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

describe("logic edits", () => {
  it("builds a body for a draft whose dates are already in the past", () => {
    const built = buildLogic(
      rule({ effectiveFrom: "2020-01-01T00:00", effectiveUntil: "2020-06-01T00:00", salience: "4" }),
      [customerLeaves],
    )
    expect(built.errors).toEqual([])
    expect(built.body).toMatchObject({
      code: "senior-discount",
      name: "Apply senior discount",
      description: "Customers aged 65 or older receive a senior discount",
      salience: 4,
    })
    expect(built.body).not.toHaveProperty("effectiveFrom")
    expect(built.body).not.toHaveProperty("effectiveUntil")
    expect(built.body).not.toHaveProperty("segments")
    expect(built.body).not.toHaveProperty("branches")
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

  it("stores two if else-if else chains on the then block and keeps when as the gate", () => {
    const started = addBranch(rule(), "t")
    expect(started.thens[0]?.branches).toHaveLength(1)
    expect(started.whens[0]?.root).toMatchObject({ field: "age", operator: ">=" })
    const open = buildRule(started, [customerLeaves])
    expect(open.body).toBeNull()
    expect(open.errors.some((error) => error.startsWith("Then block 1, branch 1, if"))).toBe(true)

    const removed = removeBranch(started, "t", started.thens[0]?.branches?.[0]?.id ?? "")
    expect(removed.thens[0]?.branches).toEqual([])
    expect(buildRule(removed, [customerLeaves]).body).toEqual(buildRule(rule(), [customerLeaves]).body)

    const tiered = rule({
      whens: [when(comparison({ field: "age", operator: ">", valueText: "18" }))],
      thens: [
        {
          id: "t",
          binding: "$customer",
          assignments: [assignment({ id: "base", field: "name", valueText: "Ada" })],
          branches: [segmentChain(), discountChain()],
        },
      ],
    })
    const built = buildRule(tiered, [customerLeaves])
    expect(built.errors).toEqual([])
    expect(built.body).not.toHaveProperty("branches")
    expect(built.body?.when).toEqual([
      { binding: "$customer", type: "Customer", conditions: { field: "age", operator: ">", value: 18 } },
    ])
    expect(built.body?.then).toEqual([
      {
        binding: "$customer",
        assignments: [{ field: "name", value: "Ada" }],
        branches: [
          {
            if: { when: { field: "age", operator: ">=", value: 65 }, assignments: [{ field: "segment", value: "SENIOR" }] },
            elseIf: [{ when: { field: "age", operator: ">=", value: 40 }, assignments: [{ field: "segment", value: "ADULT" }] }],
            else: { assignments: [{ field: "segment", value: "YOUNG" }] },
          },
          {
            if: { when: { field: "segment", operator: "==", value: "GOLD" }, assignments: [{ field: "discount", value: 0.2 }] },
            elseIf: [{ when: { field: "segment", operator: "==", value: "SILVER" }, assignments: [{ field: "discount", value: 0.1 }] }],
            else: { assignments: [{ field: "discount", value: 0.05 }] },
          },
        ],
      },
    ])

    const twice = rule({
      whens: [when(comparison({ field: "age", operator: ">", valueText: "18" }))],
      thens: [
        {
          id: "t",
          binding: "$customer",
          assignments: [],
          branches: [
            {
              ...segmentChain(),
              ifArm: {
                ...segmentChain().ifArm,
                assignments: [
                  assignment({ field: "segment", valueText: "SENIOR" }),
                  assignment({ id: "again", field: "segment", valueText: "ADULT" }),
                ],
              },
            },
          ],
        },
      ],
    })
    const duplicated = buildRule(twice, [customerLeaves])
    expect(duplicated.body).toBeNull()
    expect(duplicated.errors).toContain("Then block 1, branch 1, if: segment is assigned more than once.")
  })

  it("reads another fact from a branch and reloads the chains onto the then block", () => {
    const whens: WhenDraft[] = [
      when(comparison({ field: "age", operator: ">", valueText: "18" })),
      { id: "w2", binding: "$order", type: "Order", root: comparison({ id: "approved", field: "total", operator: ">", valueText: "0" }) },
    ]
    const leaves = chainLeaves("$order", whens, [customerLeaves, orderLeaves])
    expect(leaves.map((leaf) => leaf.label)).toEqual([
      "Customer.name",
      "Customer.age",
      "Customer.discount",
      "Customer.segment",
      "Customer.approved",
      "Customer.nickname",
      "total",
      "priority",
    ])

    const built = buildRule(
      rule({
        whens,
        thens: [
          {
            id: "order",
            binding: "$order",
            assignments: [],
            branches: [
              {
                id: "b",
                ifArm: {
                  id: "if",
                  when: comparison({
                    id: "cross",
                    field: "$customer.age",
                    operator: ">",
                    rhs: "field",
                    valueField: "total",
                  }),
                  assignments: [assignment({ id: "high", field: "priority", valueText: "HIGH" })],
                },
                elseIf: [],
                otherwise: {
                  id: "else",
                  assignments: [assignment({ id: "low", field: "priority", valueText: "LOW" })],
                },
              },
            ],
          },
        ],
      }),
      [customerLeaves, orderLeaves],
    )
    expect(built.errors).toEqual([])
    expect(built.body).not.toHaveProperty("branches")
    const then = built.body?.then as { branches: { if: { when: { valueField: string } } }[] }[]
    expect(then[0].branches[0].if.when.valueField).toBe("$order.total")

    const dates = { effectiveFrom: "2091-01-01T00:00:00", effectiveUntil: "2092-01-01T00:00:00" }
    const reloaded = draftFromVersion(
      { code: "tiered", name: "Tiered discount" },
      versionWith({
        when: [{ binding: "$customer", type: "Customer", conditions: { field: "age", operator: ">", value: 18 } }],
        then: [
          {
            binding: "$customer",
            assignments: [],
            branches: [
              {
                if: { when: { field: "$customer.age", operator: ">=", value: 65 }, assignments: [{ field: "segment", value: "SENIOR" }] },
                elseIf: [{ when: { field: "age", operator: ">=", value: 40 }, assignments: [{ field: "segment", value: "ADULT" }] }],
                else: { assignments: [{ field: "segment", value: "YOUNG" }] },
              },
            ],
          },
        ],
      }),
      dates,
    )
    expect(reloaded.whens[0]?.root).toMatchObject({ field: "age", operator: ">", valueText: "18" })
    expect(reloaded.thens[0]?.assignments).toEqual([])
    expect(reloaded.thens[0]?.branches?.[0]?.ifArm.when).toMatchObject({ field: "age", operator: ">=" })
    expect(reloaded.thens[0]?.branches?.[0]?.otherwise?.assignments[0]).toMatchObject({ field: "segment", valueText: "YOUNG" })
    const again = buildRule(reloaded, [customerLeaves])
    expect(again.errors).toEqual([])
    expect(again.body?.then).toEqual([
      {
        binding: "$customer",
        assignments: [],
        branches: [
          {
            if: { when: { field: "age", operator: ">=", value: 65 }, assignments: [{ field: "segment", value: "SENIOR" }] },
            elseIf: [{ when: { field: "age", operator: ">=", value: 40 }, assignments: [{ field: "segment", value: "ADULT" }] }],
            else: { assignments: [{ field: "segment", value: "YOUNG" }] },
          },
        ],
      },
    ])
  })

  it("derives the binding from the type and refuses a second use of that type", () => {
    const dates = { effectiveFrom: "2091-01-01T00:00:00", effectiveUntil: "2092-01-01T00:00:00" }
    const reloaded = draftFromVersion(
      { code: "alias", name: "Alias" },
      versionWith({
        when: [{ binding: "$c", type: "Customer", conditions: { field: "age", operator: ">", value: 18 } }],
        then: [{ binding: "$c", assignments: [{ field: "discount", expression: "$c.getAge() * 0.01" }] }],
      }),
      dates,
    )
    expect(reloaded.whens[0]).toMatchObject({ type: "Customer", binding: "$customer" })
    expect(reloaded.thens[0]?.assignments[0]?.expression).toBe("$customer.getAge() * 0.01")

    const draft = rule({
      whens: [
        when(comparison({ field: "age", operator: ">", valueText: "18" })),
        { id: "w2", binding: "$order", type: "Order", root: emptyComparison() },
      ],
      thens: [
        {
          id: "t",
          binding: "$customer",
          assignments: [assignment({ field: "segment", valueText: "BASE", expression: "$customer.getAge() * 0.01" })],
          branches: [
            {
              id: "b",
              ifArm: {
                id: "if",
                when: comparison({ field: "age", operator: ">", rhs: "field", valueField: "$order.total" }),
                assignments: [assignment({ id: "seg", field: "segment", valueText: "SENIOR" })],
              },
              elseIf: [],
              otherwise: null,
            },
          ],
        },
      ],
    })
    expect(changeFactType(draft, "w", "Order")).toBe(draft)
    const next = changeFactType(draft, "w", "Person")
    expect(next.whens[0]).toMatchObject({ type: "Person", binding: "$person" })
    expect(next.whens[1]).toMatchObject({ type: "Order", binding: "$order" })
    expect(next.thens[0]).toMatchObject({ binding: "$person" })
    expect(next.thens[0]?.assignments[0]?.field).toBe("")
    expect(next.thens[0]?.assignments[0]?.expression).toBe("$person.getAge() * 0.01")
    expect(next.thens[0]?.branches?.[0]?.ifArm.when).toMatchObject({ field: "" })

    const duplicate = buildRule(
      rule({
        whens: [
          when(comparison({ field: "age", operator: ">", valueText: "18" })),
          { id: "w2", binding: "$customer2", type: "Customer", root: comparison({ id: "c2", field: "age", operator: ">=", valueText: "65" }) },
        ],
      }),
      [customerLeaves, customerLeaves],
    )
    expect(duplicate.body).toBeNull()
    expect(duplicate.errors).toContain("When block 2: Customer is already used.")
    expect(duplicate.errors.join(" ")).not.toContain("$customer2")

    const labelled = valueFieldOptions(orderLeaves, [{ binding: "$customer", type: "Customer", leaves: customerLeaves }])
    expect(labelled.find((option) => option.value === "$customer.name")?.label).toBe("Customer.name")
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

  it("names the overlapped window and ignores a version's own dates", () => {
    const versions: RuleVersion[] = [
      {
        id: 1,
        versionNumber: 1,
        version: "v1",
        description: null,
        effectiveFrom: "2026-11-01T00:00:00+05:30",
        effectiveUntil: "2026-12-31T23:59:00+05:30",
        active: false,
        definition: null,
        drl: null,
      },
      {
        id: 2,
        versionNumber: 2,
        version: "v2",
        description: null,
        effectiveFrom: "2027-01-01T00:00:00+05:30",
        effectiveUntil: "2027-03-31T23:59:00+05:30",
        segments: [
          { effectiveFrom: "2027-01-01T00:00:00+05:30", effectiveUntil: "2027-01-31T23:59:00+05:30" },
          { effectiveFrom: "2027-02-01T00:00:00+05:30", effectiveUntil: "2027-03-31T23:59:00+05:30" },
        ],
        active: false,
        definition: null,
        drl: null,
      },
    ]
    const hits = overlappingWindows(
      [
        { effectiveFrom: "2026-11-01T00:00:00+05:30", effectiveUntil: "2027-01-20T23:59:00+05:30" },
        { effectiveFrom: "2027-02-01T00:00:00+05:30", effectiveUntil: "2027-02-01T23:59:00+05:30" },
      ],
      versions,
      "v1",
    )
    expect(hits.map((hit) => hit.version)).toEqual(["v2", "v2"])
    expect(hits[0]?.from).toBe(new Date("2027-01-01T00:00:00+05:30").toISOString())
    expect(hits[0]?.until).toBe(new Date("2027-01-20T23:59:00+05:30").toISOString())
    expect(hits[1]?.from).toBe(new Date("2027-02-01T00:00:00+05:30").toISOString())
    expect(hits[1]?.until).toBe(new Date("2027-02-01T23:59:00+05:30").toISOString())

    const touching = overlappingWindows(
      [{ effectiveFrom: "2026-11-01T00:00:00+05:30", effectiveUntil: "2026-12-31T23:59:00+05:30" }],
      versions,
      "v1",
    )
    expect(touching).toEqual([])
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
