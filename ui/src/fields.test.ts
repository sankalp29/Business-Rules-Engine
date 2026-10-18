import { describe, expect, it } from "vitest"
import { matchingFields } from "./fields"
import type { FieldDecl } from "./model"

const fields: FieldDecl[] = [
  { name: "age", type: "int", description: "Years since birth" },
  {
    name: "address",
    type: "Address",
    description: "Where the customer lives",
    fields: [
      { name: "city", type: "String", description: "City name" },
      { name: "pin", type: "String" },
    ],
  },
  { name: "score", type: "double" },
]

describe("matchingFields", () => {
  it("returns every field when the query is blank", () => {
    expect(matchingFields(fields, "  ")).toEqual(fields)
    expect(matchingFields(null, "age")).toEqual([])
  })

  it("matches a name, a data type, or a description", () => {
    expect(matchingFields(fields, "AGE").map((field) => field.name)).toEqual(["age"])
    expect(matchingFields(fields, "double").map((field) => field.name)).toEqual(["score"])
    expect(matchingFields(fields, "birth").map((field) => field.name)).toEqual(["age"])
  })

  it("keeps a parent when one of its fields matches, and keeps every child when the parent matches", () => {
    expect(matchingFields(fields, "city")).toEqual([
      {
        name: "address",
        type: "Address",
        description: "Where the customer lives",
        fields: [{ name: "city", type: "String", description: "City name" }],
      },
    ])
    const address = matchingFields(fields, "address")[0]
    expect(address?.fields?.map((field) => field.name)).toEqual(["city", "pin"])
  })
})
