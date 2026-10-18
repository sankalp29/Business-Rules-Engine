import type {
  Assignment,
  Condition,
  FactSchema,
  FieldDecl,
  Leaf,
  RuleVersion,
  ScalarName,
  ThenBlock,
  WhenBlock,
} from "./model"
import {
  addCalendarDays,
  isBeforeCurrentMinute,
  isWholeMinute,
  istCalendarDate,
  istInputValue,
  istInstant,
  istOffset,
  roundUpMinute,
} from "./format"
import { bindingFor, controlKind, isPrimitive, isScalar, operatorsFor, sameFamily, uid } from "./model"

export type FieldDraft = {
  id: string
  name: string
  mode: "scalar" | "type" | "object"
  scalar: ScalarName
  typeName: string
  children: FieldDraft[]
}

export function emptyField(): FieldDraft {
  return { id: uid(), name: "", mode: "scalar", scalar: "String", typeName: "", children: [] }
}

export function fieldToJson(field: FieldDraft): { name: string; type?: string; fields?: ReturnType<typeof fieldToJson>[] } {
  const name = field.name.trim()
  if (field.mode === "object") {
    return { name, fields: field.children.map(fieldToJson) }
  }
  if (field.mode === "type") {
    return { name, type: field.typeName.trim() }
  }
  return { name, type: field.scalar }
}

export function fieldErrors(field: FieldDraft, label = "Field"): string[] {
  const errors: string[] = []
  if (!field.name.trim()) errors.push(`${label}: enter a name.`)
  if (field.mode === "type" && !field.typeName.trim()) errors.push(`${label}: choose a saved type.`)
  if (field.mode === "object") {
    if (field.children.length === 0) errors.push(`${label}: add a nested field.`)
    field.children.forEach((child, index) => {
      errors.push(...fieldErrors(child, `${label} / ${child.name.trim() || `nested ${index + 1}`}`))
    })
  }
  return errors
}

export type ComparisonDraft = {
  id: string
  kind: "comparison"
  field: string
  operator: string
  rhs: "value" | "field"
  valueText: string
  valueBool: boolean
  valueNull: boolean
  valueField: string
}

export type GroupDraft = {
  id: string
  kind: "group"
  operator: "and" | "or"
  children: ConditionDraft[]
}

export type ConditionDraft = ComparisonDraft | GroupDraft

export function emptyComparison(): ComparisonDraft {
  return {
    id: uid(),
    kind: "comparison",
    field: "",
    operator: "==",
    rhs: "value",
    valueText: "",
    valueBool: false,
    valueNull: false,
    valueField: "",
  }
}

export function emptyGroup(): GroupDraft {
  return { id: uid(), kind: "group", operator: "and", children: [emptyComparison(), emptyComparison()] }
}

export type AssignmentDraft = {
  id: string
  field: string
  mode: "value" | "expression"
  valueText: string
  valueBool: boolean
  valueNull: boolean
  expression: string
}

export function emptyAssignment(): AssignmentDraft {
  return {
    id: uid(),
    field: "",
    mode: "value",
    valueText: "",
    valueBool: false,
    valueNull: false,
    expression: "",
  }
}

export type WhenDraft = {
  id: string
  binding: string
  bindingTouched: boolean
  type: string
  root: ConditionDraft
}

export function emptyWhen(type = ""): WhenDraft {
  return {
    id: uid(),
    binding: bindingFor(type),
    bindingTouched: false,
    type,
    root: emptyComparison(),
  }
}

export type ThenDraft = {
  id: string
  binding: string
  assignments: AssignmentDraft[]
}

export function emptyThen(binding = ""): ThenDraft {
  return { id: uid(), binding, assignments: [emptyAssignment()] }
}

export type PeriodDraft = {
  effectiveFrom: string
  effectiveUntil: string
}

export type RuleDraft = {
  code: string
  name: string
  description: string
  salience: string
  effectiveFrom: string
  effectiveUntil: string
  /** Further periods. The first period stays in effectiveFrom and effectiveUntil. */
  extraSegments?: PeriodDraft[]
  whens: WhenDraft[]
  thens: ThenDraft[]
}

export function shiftYears(iso: string, years: number): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})(.*)$/.exec(iso)
  if (!match) return iso
  return `${Number(match[1]) + years}-${match[2]}-${match[3]}${match[4] ?? ""}`
}

export function emptyRule(now: Date = new Date()): RuleDraft {
  const when = emptyWhen()
  const effectiveFrom = istInputValue(roundUpMinute(now))
  return {
    code: "",
    name: "",
    description: "",
    salience: "",
    effectiveFrom,
    effectiveUntil: shiftYears(effectiveFrom, 1),
    whens: [when],
    thens: [emptyThen(when.binding)],
  }
}

export function suggestNextWindow(
  until: string | null | undefined,
  now: Date = new Date(),
): { effectiveFrom: string; effectiveUntil: string } {
  const fromNow = () => {
    const effectiveFrom = istInputValue(roundUpMinute(now))
    return { effectiveFrom, effectiveUntil: shiftYears(effectiveFrom, 1) }
  }
  if (!until) return fromNow()
  const date = new Date(until)
  if (Number.isNaN(date.getTime())) return { effectiveFrom: "", effectiveUntil: "" }
  const calendar = istCalendarDate(date)
  if (!calendar || calendar.year > 2100) return { effectiveFrom: "", effectiveUntil: "" }
  const nextDay = addCalendarDays(calendar.year, calendar.month, calendar.day, 1)
  const nextStart = new Date(`${nextDay}T00:00:00+05:30`)
  if (Number.isNaN(nextStart.getTime()) || nextStart.getTime() < now.getTime()) return fromNow()
  const effectiveFrom = `${nextDay}T00:00:00`
  return { effectiveFrom, effectiveUntil: shiftYears(effectiveFrom, 1) }
}

export function dateLocks(storedFrom: string, storedUntil: string | null, now: Date = new Date()): { fromLocked: boolean; untilLocked: boolean } {
  const from = istInstant(storedFrom)
  const until = storedUntil ? istInstant(storedUntil) : null
  return {
    fromLocked: !!from && isBeforeCurrentMinute(from, now),
    untilLocked: !!until && isBeforeCurrentMinute(until, now),
  }
}

/** 00:00–23:59 IST. Today when that midnight is still the current minute, otherwise the next day. */
export function oneDayPeriod(now: Date = new Date()): PeriodDraft {
  const calendar = istCalendarDate(now)
  if (!calendar) return { effectiveFrom: "", effectiveUntil: "" }
  const today = `${calendar.year}-${pad(calendar.month)}-${pad(calendar.day)}`
  const todayStart = new Date(`${today}T00:00:00+05:30`)
  const day =
    !Number.isNaN(todayStart.getTime()) && isBeforeCurrentMinute(todayStart, now)
      ? addCalendarDays(calendar.year, calendar.month, calendar.day, 1)
      : today
  return { effectiveFrom: `${day}T00:00:00`, effectiveUntil: `${day}T23:59:00` }
}

function pad(value: number): string {
  return String(value).padStart(2, "0")
}

/**
 * Dates for `PUT .../dates`. A bound that is already before `now` is sent back unchanged.
 * An editable field the user did not touch is sent as the stored instant, so milliseconds survive.
 */
export function editEffectiveDates(input: {
  fromInput: string
  untilInput: string
  storedFrom: string
  storedUntil: string | null
  now?: Date
}): { body: { effectiveFrom: string; effectiveUntil: string } | null; errors: string[] } {
  const now = input.now ?? new Date()
  const locks = dateLocks(input.storedFrom, input.storedUntil, now)
  const errors: string[] = []
  const from = resolveBound(
    input.fromInput,
    input.storedFrom,
    locks.fromLocked,
    "Enter the time this version starts.",
    "effectiveFrom cannot change because it is already before the current time",
  )
  const until = resolveBound(
    input.untilInput,
    input.storedUntil,
    locks.untilLocked,
    "Enter the time this version ends.",
    "effectiveUntil cannot change because it is already before the current time",
  )
  errors.push(...from.errors, ...until.errors)
  const fromDate = istInstant(from.value)
  const untilDate = istInstant(until.value)
  if (from.errors.length === 0 && from.value && from.value !== input.storedFrom && !isWholeMinute(from.value)) {
    errors.push("Use a whole minute for the start.")
  }
  if (until.errors.length === 0 && until.value && until.value !== input.storedUntil && !isWholeMinute(until.value)) {
    errors.push("Use a whole minute for the end.")
  }
  if (from.errors.length === 0 && !locks.fromLocked && fromDate && isBeforeCurrentMinute(fromDate, now)) {
    errors.push("The start cannot be before the current time.")
  }
  if (until.errors.length === 0 && !locks.untilLocked && untilDate && isBeforeCurrentMinute(untilDate, now)) {
    errors.push("The end cannot be before the current time.")
  }
  if (fromDate && untilDate && untilDate.getTime() <= fromDate.getTime()) {
    errors.push("The end must be later than the start.")
  }
  if (errors.length > 0 || !from.value || !until.value) return { body: null, errors }
  return { body: { effectiveFrom: from.value, effectiveUntil: until.value }, errors }
}

export type SegmentEdit = {
  fromInput: string
  untilInput: string
  storedFrom: string | null
  storedUntil: string | null
}

/**
 * Dates for `PUT .../dates`. One period stays a single pair. Several periods replace the schedule.
 * Messages for one period stay unprefixed.
 */
export function editEffectiveSegments(input: {
  segments: SegmentEdit[]
  now?: Date
}): { body: { effectiveFrom: string; effectiveUntil: string; segments?: { effectiveFrom: string; effectiveUntil: string }[] } | null; errors: string[] } {
  if (input.segments.length === 0) return { body: null, errors: ["Add a period."] }
  if (input.segments.length === 1) {
    const only = input.segments[0]
    return editEffectiveDates({
      fromInput: only?.fromInput ?? "",
      untilInput: only?.untilInput ?? "",
      storedFrom: only?.storedFrom ?? "",
      storedUntil: only?.storedUntil ?? null,
      now: input.now,
    })
  }
  const now = input.now ?? new Date()
  const errors: string[] = []
  const segments: { effectiveFrom: string; effectiveUntil: string }[] = []
  input.segments.forEach((segment, index) => {
    const edited = editEffectiveDates({
      fromInput: segment.fromInput,
      untilInput: segment.untilInput,
      storedFrom: segment.storedFrom ?? "",
      storedUntil: segment.storedUntil,
      now,
    })
    const prefix = `Period ${index + 1}: `
    edited.errors.forEach((error) => errors.push(`${prefix}${error}`))
    if (edited.body) segments.push(edited.body)
  })
  if (segments.length === input.segments.length && minutesOverlap(segments)) {
    errors.push("Periods overlap each other.")
  }
  if (errors.length > 0 || segments.length !== input.segments.length || !segments[0]) return { body: null, errors }
  const last = segments[segments.length - 1]
  return {
    body: {
      effectiveFrom: segments[0].effectiveFrom,
      effectiveUntil: last?.effectiveUntil ?? segments[0].effectiveUntil,
      segments,
    },
    errors,
  }
}

function minutesOverlap(periods: { effectiveFrom: string; effectiveUntil: string }[]): boolean {
  const parsed: { from: number; until: number }[] = []
  for (const period of periods) {
    const from = istInstant(period.effectiveFrom)
    const until = istInstant(period.effectiveUntil)
    if (!from || !until) return false
    parsed.push({ from: Math.floor(from.getTime() / 60_000), until: Math.floor(until.getTime() / 60_000) })
  }
  parsed.sort((left, right) => left.from - right.from)
  for (let index = 1; index < parsed.length; index += 1) {
    const previous = parsed[index - 1]
    const next = parsed[index]
    if (previous && next && previous.until >= next.from) return true
  }
  return false
}

function resolveBound(
  inputValue: string,
  stored: string | null,
  locked: boolean,
  requiredMessage: string,
  lockedMessage: string,
): { value: string; errors: string[] } {
  const storedDate = stored ? istInstant(stored) : null
  const parsed = istInstant(inputValue)
  const unchanged = !!stored && (inputValue === istInputValue(stored) || (!!parsed && !!storedDate && parsed.getTime() === storedDate.getTime()))
  if (stored && (locked || unchanged)) {
    if (locked && !unchanged) return { value: stored, errors: [lockedMessage] }
    return { value: stored, errors: [] }
  }
  if (!parsed) return { value: "", errors: [requiredMessage] }
  return { value: istOffset(inputValue.trim()), errors: [] }
}

export function sanitizeNumber(raw: string, integer: boolean): string {
  const cleaned = raw.replace(integer ? /[^\d-]/g : /[^\d.-]/g, "")
  const negative = cleaned.startsWith("-")
  const body = cleaned.replace(/-/g, "")
  if (integer) return `${negative ? "-" : ""}${body}`
  const dot = body.indexOf(".")
  if (dot < 0) return `${negative ? "-" : ""}${body}`
  return `${negative ? "-" : ""}${body.slice(0, dot)}.${body.slice(dot + 1).replace(/\./g, "")}`
}

export function parseNumber(text: string, integer: boolean): number | undefined {
  const trimmed = text.trim()
  if (!trimmed || trimmed === "-" || trimmed === "." || trimmed === "-.") return undefined
  const pattern = integer ? /^-?\d+$/ : /^-?(?:\d+\.\d+|\d+\.|\.\d+|\d+)$/
  if (!pattern.test(trimmed)) return undefined
  const value = Number(trimmed)
  return Number.isFinite(value) ? value : undefined
}

export type DraftValue =
  | { kind: "string"; text: string }
  | { kind: "number"; text: string; integer: boolean }
  | { kind: "boolean"; value: boolean }
  | { kind: "object"; children: Record<string, DraftValue> }

export function draftForField(field: FieldDecl): DraftValue {
  if (field.fields && field.fields.length > 0) {
    return { kind: "object", children: draftFromFields(field.fields) }
  }
  if (field.type && !isScalar(field.type)) {
    return { kind: "object", children: {} }
  }
  const kind = controlKind(field.type)
  if (kind === "boolean") return { kind: "boolean", value: false }
  if (kind === "integer") return { kind: "number", text: "", integer: true }
  if (kind === "decimal") return { kind: "number", text: "", integer: false }
  return { kind: "string", text: "" }
}

export function draftFromFields(fields: FieldDecl[] | null | undefined): Record<string, DraftValue> {
  const draft: Record<string, DraftValue> = {}
  for (const field of fields ?? []) {
    if (!field?.name) continue
    draft[field.name] = draftForField(field)
  }
  return draft
}

export function serializeDraft(draft: Record<string, DraftValue>): Record<string, unknown> {
  const values: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(draft)) {
    const serialized = serializeValue(value)
    if (serialized !== undefined) values[key] = serialized
  }
  return values
}

function serializeValue(value: DraftValue): unknown | undefined {
  if (value.kind === "boolean") return value.value
  if (value.kind === "string") return value.text === "" ? undefined : value.text
  if (value.kind === "number") return parseNumber(value.text, value.integer)
  const nested = serializeDraft(value.children)
  return Object.keys(nested).length > 0 ? nested : undefined
}

export function incompleteNumbers(draft: Record<string, DraftValue>, prefix = ""): string[] {
  const errors: string[] = []
  for (const [key, value] of Object.entries(draft)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value.kind === "number" && value.text.trim() && parseNumber(value.text, value.integer) === undefined) {
      errors.push(`${path} needs a ${value.integer ? "whole number" : "number"}.`)
    } else if (value.kind === "object") {
      errors.push(...incompleteNumbers(value.children, path))
    }
  }
  return errors
}

export type FactDraft = {
  id: string
  type: string
  fields: FieldDecl[]
  values: Record<string, DraftValue>
}

export function factsFromSchema(schema: FactSchema[] | null | undefined): FactDraft[] {
  return (schema ?? []).map((fact) => ({
    id: uid(),
    type: fact.type,
    fields: fact.fields ?? [],
    values: draftFromFields(fact.fields),
  }))
}

export function executePayload(facts: FactDraft[]): { facts: { type: string; values: Record<string, unknown> }[] } {
  return {
    facts: facts.map((fact) => ({
      type: fact.type,
      values: serializeDraft(fact.values),
    })),
  }
}

export type ValueFieldOption = {
  value: string
  type: string
  label: string
}

export function valueFieldOptions(currentLeaves: Leaf[], earlier: { binding: string; leaves: Leaf[] }[]): ValueFieldOption[] {
  const options = currentLeaves.map((leaf) => ({ value: leaf.path, type: leaf.type, label: leaf.path }))
  for (const block of earlier) {
    const binding = block.binding.trim()
    if (!binding) continue
    for (const leaf of block.leaves) {
      const value = `${binding}.${leaf.path}`
      options.push({ value, type: leaf.type, label: value })
    }
  }
  return options
}

export function compatibleFields(options: ValueFieldOption[], type: string | undefined): ValueFieldOption[] {
  if (!type) return []
  return options.filter((option) => sameFamily(type, option.type))
}

export type RuleBuild = {
  body: Record<string, unknown> | null
  errors: string[]
}

export function buildRule(draft: RuleDraft, leavesByWhen: Leaf[][], now: Date = new Date()): RuleBuild {
  const errors: string[] = []
  const code = draft.code.trim()
  const name = draft.name.trim()
  if (!code) errors.push("Enter a code.")
  else if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(code)) {
    errors.push("Code must start with a letter and use only letters, digits, underscores, or hyphens.")
  }
  if (!name) errors.push("Enter a name.")

  const periods: PeriodDraft[] = [
    { effectiveFrom: draft.effectiveFrom.trim(), effectiveUntil: draft.effectiveUntil.trim() },
    ...(draft.extraSegments ?? []).map((segment) => ({
      effectiveFrom: segment.effectiveFrom.trim(),
      effectiveUntil: segment.effectiveUntil.trim(),
    })),
  ]
  const wall = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/
  const builtPeriods: { effectiveFrom: string; effectiveUntil: string }[] = []
  periods.forEach((period, index) => {
    const prefix = periods.length > 1 ? `Period ${index + 1}: ` : ""
    const fromDate = wall.test(period.effectiveFrom) ? istInstant(period.effectiveFrom) : null
    const untilDate = wall.test(period.effectiveUntil) ? istInstant(period.effectiveUntil) : null
    if (!fromDate) errors.push(`${prefix}Enter the time this version starts.`)
    else if (!isWholeMinute(period.effectiveFrom)) errors.push(`${prefix}Use a whole minute for the start.`)
    else if (isBeforeCurrentMinute(fromDate, now)) errors.push(`${prefix}The start cannot be before the current time.`)
    if (!untilDate) errors.push(`${prefix}Enter the time this version ends.`)
    else if (!isWholeMinute(period.effectiveUntil)) errors.push(`${prefix}Use a whole minute for the end.`)
    else if (isBeforeCurrentMinute(untilDate, now)) errors.push(`${prefix}The end cannot be before the current time.`)
    if (fromDate && untilDate && untilDate.getTime() <= fromDate.getTime()) {
      errors.push(`${prefix}The end must be later than the start.`)
    }
    if (fromDate && untilDate) {
      builtPeriods.push({ effectiveFrom: istOffset(period.effectiveFrom), effectiveUntil: istOffset(period.effectiveUntil) })
    }
  })
  if (builtPeriods.length === periods.length && builtPeriods.length > 1 && minutesOverlap(builtPeriods)) {
    errors.push("Periods overlap each other.")
  }

  let salience: number | undefined
  if (draft.salience.trim()) {
    const parsed = parseNumber(draft.salience.trim(), true)
    if (parsed === undefined) errors.push("Salience must be a whole number.")
    else salience = parsed
  }

  const seenBindings = new Set<string>()
  const when: WhenBlock[] = []
  draft.whens.forEach((block, index) => {
    const built = serializeWhen(block, leavesByWhen[index] ?? [], index, errors)
    if (!built) return
    if (seenBindings.has(built.binding)) errors.push(`When block ${index + 1}: ${built.binding} is already used.`)
    seenBindings.add(built.binding)
    when.push(built)
  })

  const then: ThenBlock[] = []
  draft.thens.forEach((block, index) => {
    const built = serializeThen(block, index, draft.whens, leavesByWhen, errors)
    if (built) then.push(built)
  })

  if (errors.length > 0 || when.length !== draft.whens.length || then.length !== draft.thens.length) {
    return { body: null, errors }
  }

  const first = builtPeriods[0]
  if (!first) return { body: null, errors }
  const body: Record<string, unknown> = {
    code,
    name,
    effectiveFrom: first.effectiveFrom,
    effectiveUntil: first.effectiveUntil,
  }
  if (builtPeriods.length > 1) body.segments = builtPeriods
  const description = draft.description.trim()
  if (description) body.description = description
  if (salience !== undefined) body.salience = salience
  body.when = when
  body.then = then
  return { body, errors }
}

export function draftFromVersion(
  rule: { code: string | null; name: string | null },
  version: RuleVersion,
  dates: { effectiveFrom: string; effectiveUntil: string },
): RuleDraft {
  const definition = version.definition
  const whens = (definition?.when ?? []).map(whenFromBlock)
  const whenBlocks = whens.length > 0 ? whens : [emptyWhen()]
  const thens = (definition?.then ?? []).map(thenFromBlock)
  return {
    code: rule.code ?? "",
    name: rule.name ?? "",
    description: version.description ?? "",
    salience: definition?.salience == null ? "" : String(definition.salience),
    effectiveFrom: dates.effectiveFrom,
    effectiveUntil: dates.effectiveUntil,
    whens: whenBlocks,
    thens: thens.length > 0 ? thens : [emptyThen(whenBlocks[0]?.binding ?? "")],
  }
}

function whenFromBlock(block: WhenBlock): WhenDraft {
  return {
    id: uid(),
    binding: block.binding,
    bindingTouched: true,
    type: block.type,
    root: conditionFrom(block.conditions),
  }
}

function conditionFrom(condition: Condition | null | undefined): ConditionDraft {
  if (!condition || typeof condition.operator !== "string") return emptyComparison()
  if ((condition.operator === "and" || condition.operator === "or") && Array.isArray(condition.conditions)) {
    const children = condition.conditions.map((child) => conditionFrom(child))
    return {
      id: uid(),
      kind: "group",
      operator: condition.operator,
      children: children.length >= 2 ? children : [children[0] ?? emptyComparison(), emptyComparison()],
    }
  }
  const literal = literalDraft(condition.value)
  return {
    ...emptyComparison(),
    field: condition.field ?? "",
    operator: condition.operator || "==",
    rhs: condition.valueField ? "field" : "value",
    valueField: condition.valueField ?? "",
    valueText: literal.valueText,
    valueBool: literal.valueBool,
    valueNull: literal.valueNull,
  }
}

function thenFromBlock(block: ThenBlock): ThenDraft {
  const assignments = (block.assignments ?? []).map((assignment) => {
    if (assignment.expression) {
      return { ...emptyAssignment(), field: assignment.field, mode: "expression" as const, expression: assignment.expression }
    }
    const literal = literalDraft(assignment.value)
    return {
      ...emptyAssignment(),
      field: assignment.field,
      mode: "value" as const,
      valueText: literal.valueText,
      valueBool: literal.valueBool,
      valueNull: literal.valueNull,
    }
  })
  return {
    id: uid(),
    binding: block.binding,
    assignments: assignments.length > 0 ? assignments : [emptyAssignment()],
  }
}

function literalDraft(value: unknown): { valueText: string; valueBool: boolean; valueNull: boolean } {
  if (value === null) return { valueText: "", valueBool: false, valueNull: true }
  if (typeof value === "boolean") return { valueText: "", valueBool: value, valueNull: false }
  if (typeof value === "number") return { valueText: String(value), valueBool: false, valueNull: false }
  if (typeof value === "string") return { valueText: value, valueBool: false, valueNull: false }
  return { valueText: "", valueBool: false, valueNull: false }
}

function serializeWhen(block: WhenDraft, leaves: Leaf[], index: number, errors: string[]): WhenBlock | null {
  const label = `When block ${index + 1}`
  const binding = block.binding.trim()
  if (!/^\$[A-Za-z_][A-Za-z0-9_]*$/.test(binding)) errors.push(`${label}: binding must look like $customer.`)
  if (!block.type) errors.push(`${label}: choose a type.`)
  const conditions = serializeCondition(block.root, leaves, label, errors)
  if (!binding || !block.type || !conditions) return null
  return { binding, type: block.type, conditions }
}

function serializeCondition(node: ConditionDraft, leaves: Leaf[], label: string, errors: string[]): Condition | null {
  if (node.kind === "group") {
    if (node.children.length < 2) {
      errors.push(`${label}: ${node.operator} needs at least two conditions.`)
      return null
    }
    const children: Condition[] = []
    node.children.forEach((child, index) => {
      const built = serializeCondition(child, leaves, `${label}, condition ${index + 1}`, errors)
      if (built) children.push(built)
    })
    if (children.length !== node.children.length) return null
    return { operator: node.operator, conditions: children }
  }

  const leaf = leaves.find((item) => item.path === node.field)
  if (!leaf) {
    errors.push(`${label}: choose a field.`)
    return null
  }
  if (!operatorsFor(leaf.type).includes(node.operator)) {
    errors.push(`${label}: ${node.operator} does not apply to ${leaf.type}.`)
    return null
  }
  if (node.rhs === "field") {
    if (!node.valueField.trim()) {
      errors.push(`${label}: choose a field to compare with.`)
      return null
    }
    return { field: node.field, operator: node.operator, valueField: node.valueField.trim() }
  }
  const literal = literalValue(leaf.type, node.valueText, node.valueBool, node.valueNull, label, node.field, errors)
  if (literal === NOT_READY) return null
  return { field: node.field, operator: node.operator, value: literal }
}

function serializeThen(
  block: ThenDraft,
  index: number,
  whens: WhenDraft[],
  leavesByWhen: Leaf[][],
  errors: string[],
): ThenBlock | null {
  const label = `Then block ${index + 1}`
  const binding = block.binding.trim()
  const whenIndex = whens.findIndex((when) => when.binding.trim() === binding)
  if (whenIndex < 0) {
    errors.push(`${label}: choose a when binding.`)
    return null
  }
  if (block.assignments.length === 0) {
    errors.push(`${label}: add an assignment.`)
    return null
  }
  const leaves = leavesByWhen[whenIndex] ?? []
  const assignments: Assignment[] = []
  const seen = new Set<string>()
  let complete = true
  block.assignments.forEach((assignment, assignmentIndex) => {
    const built = serializeAssignment(assignment, leaves, `${label}, assignment ${assignmentIndex + 1}`, errors)
    if (!built) {
      complete = false
      return
    }
    if (seen.has(built.field)) errors.push(`${label}: ${built.field} is assigned more than once.`)
    seen.add(built.field)
    assignments.push(built)
  })
  if (!complete || assignments.length !== block.assignments.length) return null
  return { binding, assignments }
}

const NOT_READY = Symbol("not-ready")

function literalValue(
  type: string,
  text: string,
  bool: boolean,
  useNull: boolean,
  label: string,
  field: string,
  errors: string[],
): unknown {
  if (useNull) {
    if (isPrimitive(type)) {
      errors.push(`${label}: ${type} cannot be null.`)
      return NOT_READY
    }
    return null
  }
  const kind = controlKind(type)
  if (kind === "boolean") return bool
  if (kind === "integer" || kind === "decimal") {
    const parsed = parseNumber(text, kind === "integer")
    if (parsed === undefined) {
      errors.push(`${label}: enter a ${kind === "integer" ? "whole number" : "number"} for ${field}.`)
      return NOT_READY
    }
    return parsed
  }
  if (text === "") {
    errors.push(`${label}: enter text for ${field}.`)
    return NOT_READY
  }
  return text
}

function serializeAssignment(draft: AssignmentDraft, leaves: Leaf[], label: string, errors: string[]): Assignment | null {
  const leaf = leaves.find((item) => item.path === draft.field)
  if (!leaf) {
    errors.push(`${label}: choose a field.`)
    return null
  }
  if (draft.mode === "expression") {
    const expression = draft.expression.trim()
    if (!expression) {
      errors.push(`${label}: enter an expression.`)
      return null
    }
    return { field: draft.field, expression }
  }
  const value = literalValue(leaf.type, draft.valueText, draft.valueBool, draft.valueNull, label, draft.field, errors)
  if (value === NOT_READY) return null
  return { field: draft.field, value }
}
