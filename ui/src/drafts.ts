import type {
  Assignment,
  Branch,
  BranchArm,
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
  periodsOf,
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

/** One arm. Else omits `when`. */
export type ArmDraft = {
  id: string
  when?: ConditionDraft
  assignments: AssignmentDraft[]
}

/** An if / else-if / else chain on one then block. */
export type BranchDraft = {
  id: string
  ifArm: ArmDraft
  elseIf: ArmDraft[]
  otherwise: ArmDraft | null
}

export type ThenDraft = {
  id: string
  binding: string
  assignments: AssignmentDraft[]
  /** Exclusive chains. They run after the assignments, once the when condition matches. */
  branches?: BranchDraft[]
}

export function emptyThen(binding = ""): ThenDraft {
  return { id: uid(), binding, assignments: [emptyAssignment()], branches: [] }
}

export function emptyArm(withWhen = true): ArmDraft {
  return {
    id: uid(),
    ...(withWhen ? { when: emptyComparison() } : {}),
    assignments: [emptyAssignment()],
  }
}

export function emptyBranch(): BranchDraft {
  return { id: uid(), ifArm: emptyArm(true), elseIf: [], otherwise: null }
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
  const echoedPair = from.value === input.storedFrom && until.value === input.storedUntil
  if (fromDate && untilDate && untilDate.getTime() < fromDate.getTime()) {
    errors.push("The end must be later than the start.")
  } else if (fromDate && untilDate && untilDate.getTime() === fromDate.getTime() && !echoedPair) {
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

export type DateOverlap = {
  version: string
  from: string
  until: string | null
}

/** Minutes another version already owns inside the dates about to be saved. */
export function overlappingWindows(
  proposed: readonly { effectiveFrom: string; effectiveUntil: string }[],
  versions: readonly Pick<RuleVersion, "version" | "effectiveFrom" | "effectiveUntil" | "segments">[],
  currentVersion: string,
): DateOverlap[] {
  const hits: DateOverlap[] = []
  const seen = new Set<string>()
  for (const version of versions) {
    if (version.version === currentVersion) continue
    for (const period of periodsOf(version)) {
      for (const next of proposed) {
        const overlap = minuteIntersection(next.effectiveFrom, next.effectiveUntil, period.effectiveFrom, period.effectiveUntil)
        if (!overlap) continue
        const key = `${version.version}|${overlap.from}|${overlap.until ?? "open"}`
        if (seen.has(key)) continue
        seen.add(key)
        hits.push({ version: version.version, from: overlap.from, until: overlap.until })
      }
    }
  }
  return hits
}

export function periodsOfSubmission(body: {
  effectiveFrom?: unknown
  effectiveUntil?: unknown
  segments?: unknown
}): { effectiveFrom: string; effectiveUntil: string }[] {
  if (Array.isArray(body.segments) && body.segments.length > 0) {
    return body.segments.flatMap((segment) => {
      if (!segment || typeof segment !== "object") return []
      const from = "effectiveFrom" in segment ? segment.effectiveFrom : null
      const until = "effectiveUntil" in segment ? segment.effectiveUntil : null
      if (typeof from !== "string" || typeof until !== "string") return []
      return [{ effectiveFrom: from, effectiveUntil: until }]
    })
  }
  if (typeof body.effectiveFrom === "string" && typeof body.effectiveUntil === "string") {
    return [{ effectiveFrom: body.effectiveFrom, effectiveUntil: body.effectiveUntil }]
  }
  return []
}

function minuteIntersection(
  aFrom: string,
  aUntil: string,
  bFrom: string,
  bUntil: string | null,
): { from: string; until: string | null } | null {
  const startA = minuteOf(aFrom)
  const endA = minuteOf(aUntil)
  const startB = minuteOf(bFrom)
  const endB = bUntil ? minuteOf(bUntil) : null
  if (startA == null || endA == null || startB == null || (bUntil != null && endB == null)) return null
  if (endA < startB) return null
  if (endB != null && endB < startA) return null
  const from = Math.max(startA, startB)
  const until = endB == null ? endA : Math.min(endA, endB)
  if (until < from) return null
  return { from: new Date(from * 60_000).toISOString(), until: new Date(until * 60_000).toISOString() }
}

function minuteOf(value: string | null | undefined): number | null {
  if (!value) return null
  const date = istInstant(value)
  if (!date) return null
  return Math.floor(date.getTime() / 60_000)
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

/** Type name, or "Customer 1" when the same type is bound more than once. A fact with no type is "Fact". */
export function factLabels(facts: readonly { binding: string; type: string }[]): Map<string, string> {
  const counts = new Map<string, number>()
  let untyped = 0
  for (const fact of facts) {
    if (!fact.binding.trim()) continue
    const type = fact.type.trim()
    if (!type) {
      untyped += 1
      continue
    }
    counts.set(type, (counts.get(type) ?? 0) + 1)
  }
  const seen = new Map<string, number>()
  const labels = new Map<string, string>()
  for (const fact of facts) {
    const binding = fact.binding.trim()
    if (!binding || labels.has(binding)) continue
    const type = fact.type.trim()
    if (!type) {
      const index = (seen.get("") ?? 0) + 1
      seen.set("", index)
      labels.set(binding, untyped > 1 ? `Fact ${index}` : "Fact")
      continue
    }
    const index = (seen.get(type) ?? 0) + 1
    seen.set(type, index)
    const total = counts.get(type) ?? 1
    labels.set(binding, total > 1 ? `${type} ${index}` : type)
  }
  return labels
}

/**
 * Condition menus for a chain on `binding`.
 * This fact's fields stay local (`age`). Every other fact is `$binding.field`, labelled `Order.total`.
 */
export function chainLeaves(binding: string, whens: readonly WhenDraft[], leavesByWhen: readonly Leaf[][]): Leaf[] {
  const labels = factLabels(whens)
  const self = binding.trim()
  const leaves: Leaf[] = []
  whens.forEach((when, index) => {
    const whenBinding = when.binding.trim()
    if (!whenBinding) return
    const own = whenBinding === self
    const typeLabel = labels.get(whenBinding) ?? (when.type.trim() || whenBinding.slice(1))
    for (const leaf of leavesByWhen[index] ?? []) {
      if (own) {
        leaves.push({ path: leaf.path, type: leaf.type, label: leaf.path })
      } else {
        leaves.push({
          path: `${whenBinding}.${leaf.path}`,
          type: leaf.type,
          label: `${typeLabel}.${leaf.path}`,
        })
      }
    }
  })
  return leaves
}

export function chainValueFields(binding: string, whens: readonly WhenDraft[], leavesByWhen: readonly Leaf[][]): ValueFieldOption[] {
  return chainLeaves(binding, whens, leavesByWhen).map((leaf) => ({
    value: leaf.path,
    type: leaf.type,
    label: leaf.label ?? leaf.path,
  }))
}

export function isBlankCondition(node: ConditionDraft): boolean {
  return node.kind === "comparison" && node.field.trim() === ""
}

export function uniqueBinding(typeName: string, taken: readonly string[]): string {
  const base = bindingFor(typeName)
  const used = new Set(taken.map((item) => item.trim()).filter(Boolean))
  if (!used.has(base)) return base
  let suffix = 2
  while (used.has(`${base}${suffix}`)) suffix += 1
  return `${base}${suffix}`
}

/** Append an empty if / else-if / else chain to one then block. */
export function addBranch(draft: RuleDraft, thenId: string): RuleDraft {
  return {
    ...draft,
    thens: draft.thens.map((block) =>
      block.id === thenId ? { ...block, branches: [...(block.branches ?? []), emptyBranch()] } : block,
    ),
  }
}

export function addFact(draft: RuleDraft): RuleDraft {
  return { ...draft, whens: [...draft.whens, emptyWhen()] }
}

/** Drop one chain. A then block left with no assignments and no chains gets one blank assignment. */
export function removeBranch(draft: RuleDraft, thenId: string, branchId: string): RuleDraft {
  return {
    ...draft,
    thens: draft.thens.map((block) => {
      if (block.id !== thenId) return block
      const branches = (block.branches ?? []).filter((branch) => branch.id !== branchId)
      const assignments = branches.length === 0 && block.assignments.length === 0 ? [emptyAssignment()] : block.assignments
      return { ...block, branches, assignments }
    }),
  }
}

export function changeFactType(draft: RuleDraft, id: string, type: string): RuleDraft {
  const when = draft.whens.find((item) => item.id === id)
  if (!when) return draft
  const typeChanged = type !== when.type
  const others = draft.whens.filter((item) => item.id !== id).map((item) => item.binding.trim())
  const binding = when.bindingTouched ? when.binding : uniqueBinding(type, others)
  return replaceWhen(draft, id, {
    ...when,
    type,
    binding,
    root: typeChanged ? emptyComparison() : when.root,
  })
}

export function replaceWhen(draft: RuleDraft, id: string, nextWhen: WhenDraft): RuleDraft {
  const previous = draft.whens.find((when) => when.id === id)
  if (!previous) return draft
  let next: RuleDraft = { ...draft, whens: draft.whens.map((when) => (when.id === id ? nextWhen : when)) }
  if (previous.binding !== nextWhen.binding) next = renameBinding(next, previous.binding, nextWhen.binding)
  if (previous.type !== nextWhen.type) next = clearFactFields(next, nextWhen.binding.trim())
  return next
}

export function removeWhen(draft: RuleDraft, id: string): RuleDraft {
  if (draft.whens.length < 2) return draft
  const removed = draft.whens.find((when) => when.id === id)
  if (!removed) return draft
  const whens = draft.whens.filter((when) => when.id !== id)
  const alive = new Set(whens.map((when) => when.binding))
  const fallback = whens[0]?.binding ?? ""
  const removedBinding = removed.binding.trim()
  return {
    ...draft,
    whens,
    thens: draft.thens.map((block) => {
      const orphan = !alive.has(block.binding)
      const next = orphan ? { ...block, binding: fallback, assignments: block.assignments.map(clearAssignmentField) } : block
      return {
        ...next,
        branches: (next.branches ?? []).map((branch) =>
          mapBranch(branch, (node) => {
            const cleared = clearConditionBinding(node, removedBinding)
            return orphan ? clearLocalCondition(cleared) : cleared
          }),
        ),
      }
    }),
  }
}

function renameBinding(draft: RuleDraft, from: string, to: string): RuleDraft {
  if (!from || from === to) return draft
  return {
    ...draft,
    thens: draft.thens.map((block) => ({
      ...block,
      binding: block.binding === from ? to : block.binding,
      branches: (block.branches ?? []).map((branch) => mapBranch(branch, (node) => renameCondition(node, from, to))),
    })),
  }
}

/** The fact's fields changed, so drop assignments and comparisons that still name them. */
function clearFactFields(draft: RuleDraft, binding: string): RuleDraft {
  if (!binding) return draft
  return {
    ...draft,
    thens: draft.thens.map((block) => {
      const own = block.binding.trim() === binding
      return {
        ...block,
        assignments: own ? block.assignments.map(clearAssignmentField) : block.assignments,
        branches: (block.branches ?? []).map((branch) => mapBranch(branch, (node) => clearFactCondition(node, binding, own))),
      }
    }),
  }
}

function clearAssignmentField(assignment: AssignmentDraft): AssignmentDraft {
  return { ...assignment, field: "", valueText: "", valueBool: false, valueNull: false }
}

function mapBranch(branch: BranchDraft, mapNode: (node: ConditionDraft) => ConditionDraft): BranchDraft {
  const mapArm = (arm: ArmDraft): ArmDraft => (arm.when ? { ...arm, when: mapNode(arm.when) } : arm)
  return {
    ...branch,
    ifArm: mapArm(branch.ifArm),
    elseIf: branch.elseIf.map(mapArm),
    otherwise: branch.otherwise ? mapArm(branch.otherwise) : null,
  }
}

function renameCondition(node: ConditionDraft, from: string, to: string): ConditionDraft {
  if (node.kind === "group") return { ...node, children: node.children.map((child) => renameCondition(child, from, to)) }
  return { ...node, field: renamePath(node.field, from, to), valueField: renamePath(node.valueField, from, to) }
}

function renamePath(path: string, from: string, to: string): string {
  if (path === from) return to
  const prefix = `${from}.`
  if (path.startsWith(prefix)) return `${to}${path.slice(from.length)}`
  return path
}

function clearConditionBinding(node: ConditionDraft, binding: string): ConditionDraft {
  if (!binding) return node
  if (node.kind === "group") return { ...node, children: node.children.map((child) => clearConditionBinding(child, binding)) }
  const prefix = `${binding}.`
  const fieldHit = node.field === binding || node.field.startsWith(prefix)
  const valueHit = node.valueField === binding || node.valueField.startsWith(prefix)
  if (fieldHit) {
    return { ...node, field: "", operator: "==", rhs: "value", valueText: "", valueBool: false, valueNull: false, valueField: "" }
  }
  if (valueHit) return { ...node, valueField: "" }
  return node
}

function clearFactCondition(node: ConditionDraft, binding: string, own: boolean): ConditionDraft {
  if (node.kind === "group") {
    return { ...node, children: node.children.map((child) => clearFactCondition(child, binding, own)) }
  }
  const fieldHit = pathBelongsToFact(node.field, binding, own)
  const valueHit = node.rhs === "field" && pathBelongsToFact(node.valueField, binding, own)
  if (fieldHit) {
    return { ...node, field: "", operator: "==", rhs: "value", valueText: "", valueBool: false, valueNull: false, valueField: "" }
  }
  if (valueHit) return { ...node, valueField: "" }
  return node
}

function clearLocalCondition(node: ConditionDraft): ConditionDraft {
  if (node.kind === "group") return { ...node, children: node.children.map(clearLocalCondition) }
  const field = node.field.trim()
  if (field.startsWith("$") || field === "") {
    if (node.rhs === "field" && node.valueField.trim() && !node.valueField.trim().startsWith("$")) {
      return { ...node, valueField: "" }
    }
    return node
  }
  return { ...node, field: "", operator: "==", rhs: "value", valueText: "", valueBool: false, valueNull: false, valueField: "" }
}

function pathBelongsToFact(path: string, binding: string, own: boolean): boolean {
  const trimmed = path.trim()
  if (!trimmed) return false
  if (trimmed === binding || trimmed.startsWith(`${binding}.`)) return true
  return own && !trimmed.startsWith("$")
}

export type RuleBuild = {
  body: Record<string, unknown> | null
  errors: string[]
}

/** Description, salience, when, and then. Dates stay on the version and are not part of this body. */
export function buildLogic(draft: RuleDraft, leavesByWhen: Leaf[][]): RuleBuild {
  const errors: string[] = []
  const identity = ruleIdentity(draft, errors)
  const parts = logicParts(draft, leavesByWhen, errors)
  if (!identity || errors.length > 0 || !parts) return { body: null, errors }
  return { body: logicBody(identity, parts), errors }
}

export function buildRule(draft: RuleDraft, leavesByWhen: Leaf[][], now: Date = new Date()): RuleBuild {
  const errors: string[] = []
  const identity = ruleIdentity(draft, errors)

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

  const parts = logicParts(draft, leavesByWhen, errors)
  if (!identity || errors.length > 0 || !parts) return { body: null, errors }

  const first = builtPeriods[0]
  if (!first) return { body: null, errors }
  const body: Record<string, unknown> = {
    code: identity.code,
    name: identity.name,
    effectiveFrom: first.effectiveFrom,
    effectiveUntil: first.effectiveUntil,
  }
  if (builtPeriods.length > 1) body.segments = builtPeriods
  if (parts.description) body.description = parts.description
  if (parts.salience !== undefined) body.salience = parts.salience
  body.when = parts.when
  body.then = parts.then
  return { body, errors }
}

function ruleIdentity(draft: RuleDraft, errors: string[]): { code: string; name: string } | null {
  const code = draft.code.trim()
  const name = draft.name.trim()
  if (!code) errors.push("Enter a code.")
  else if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(code)) {
    errors.push("Code must start with a letter and use only letters, digits, underscores, or hyphens.")
  }
  if (!name) errors.push("Enter a name.")
  if (!code || !name || errors.length > 0) return null
  return { code, name }
}

type LogicParts = {
  description: string
  salience?: number
  when: WhenBlock[]
  then: ThenBlock[]
}

function logicParts(draft: RuleDraft, leavesByWhen: Leaf[][], errors: string[]): LogicParts | null {
  let salience: number | undefined
  if (draft.salience.trim()) {
    const parsed = parseNumber(draft.salience.trim(), true)
    if (parsed === undefined) errors.push("Salience must be a whole number.")
    else salience = parsed
  }

  const description = draft.description.trim()
  const when = serializeWhens(draft.whens, leavesByWhen, errors, false)
  const then: ThenBlock[] = []
  draft.thens.forEach((block, index) => {
    const built = serializeThen(block, index, draft.whens, leavesByWhen, errors)
    if (built) then.push(built)
  })
  if (when.length !== draft.whens.length || then.length !== draft.thens.length) return null
  return { description, salience, when, then }
}

function serializeWhens(whens: WhenDraft[], leavesByWhen: Leaf[][], errors: string[], allowBlank: boolean): WhenBlock[] {
  const seen = new Set<string>()
  const when: WhenBlock[] = []
  whens.forEach((block, index) => {
    const built = serializeWhen(block, leavesByWhen[index] ?? [], index, errors, allowBlank)
    if (!built) return
    const label = allowBlank ? `Fact ${index + 1}` : `When block ${index + 1}`
    if (seen.has(built.binding)) {
      errors.push(allowBlank ? `${label} uses the same name as an earlier fact.` : `${label}: ${built.binding} is already used.`)
    }
    seen.add(built.binding)
    when.push(built)
  })
  return when
}

function logicBody(identity: { code: string; name: string }, parts: LogicParts): Record<string, unknown> {
  const body: Record<string, unknown> = { code: identity.code, name: identity.name }
  if (parts.description) body.description = parts.description
  if (parts.salience !== undefined) body.salience = parts.salience
  body.when = parts.when
  body.then = parts.then
  return body
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

function branchFromStored(branch: Branch, binding: string): BranchDraft {
  return {
    id: uid(),
    ifArm: armFromStored(branch.if, binding, true),
    elseIf: (branch.elseIf ?? []).map((arm) => armFromStored(arm, binding, true)),
    otherwise: branch.else ? armFromStored(branch.else, binding, false) : null,
  }
}

function armFromStored(arm: BranchArm | null | undefined, binding: string, withWhen: boolean): ArmDraft {
  const assignments = assignmentsFrom(arm?.assignments)
  return {
    id: uid(),
    ...(withWhen ? { when: localizeCondition(conditionFrom(arm?.when), binding) } : {}),
    assignments: assignments.length > 0 ? assignments : [emptyAssignment()],
  }
}

function thenFromBlock(block: ThenBlock): ThenDraft {
  const assignments = assignmentsFrom(block.assignments)
  const branches = (block.branches ?? []).filter((branch): branch is Branch => branch != null).map((branch) => branchFromStored(branch, block.binding))
  return {
    id: uid(),
    binding: block.binding,
    assignments: assignments.length > 0 ? assignments : branches.length > 0 ? [] : [emptyAssignment()],
    branches,
  }
}

function assignmentsFrom(assignments: Assignment[] | null | undefined): AssignmentDraft[] {
  return (assignments ?? []).map((assignment) => {
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
}

function localizeCondition(node: ConditionDraft, binding: string): ConditionDraft {
  if (node.kind === "group") {
    return { ...node, children: node.children.map((child) => localizeCondition(child, binding)) }
  }
  const prefix = `${binding}.`
  return {
    ...node,
    field: node.field.startsWith(prefix) ? node.field.slice(prefix.length) : node.field,
    valueField: node.rhs === "field" && node.valueField.startsWith(prefix) ? node.valueField.slice(prefix.length) : node.valueField,
  }
}

function literalDraft(value: unknown): { valueText: string; valueBool: boolean; valueNull: boolean } {
  if (value === null) return { valueText: "", valueBool: false, valueNull: true }
  if (typeof value === "boolean") return { valueText: "", valueBool: value, valueNull: false }
  if (typeof value === "number") return { valueText: String(value), valueBool: false, valueNull: false }
  if (typeof value === "string") return { valueText: value, valueBool: false, valueNull: false }
  return { valueText: "", valueBool: false, valueNull: false }
}

function serializeWhen(block: WhenDraft, leaves: Leaf[], index: number, errors: string[], allowBlank = false): WhenBlock | null {
  const label = allowBlank ? `Fact ${index + 1}` : `When block ${index + 1}`
  const binding = block.binding.trim()
  const bindingOk = /^\$[A-Za-z_][A-Za-z0-9_]*$/.test(binding)
  if (!bindingOk) errors.push(`${label}: binding must look like $customer.`)
  if (!block.type) errors.push(`${label}: choose a type.`)
  if (allowBlank && isBlankCondition(block.root)) {
    if (!bindingOk || !block.type) return null
    return { binding, type: block.type }
  }
  const conditions = serializeCondition(block.root, leaves, label, errors)
  if (!bindingOk || !block.type || !conditions) return null
  return { binding, type: block.type, conditions }
}

function serializeCondition(
  node: ConditionDraft,
  leaves: Leaf[],
  label: string,
  errors: string[],
  ownerBinding?: string,
): Condition | null {
  if (node.kind === "group") {
    if (node.children.length < 2) {
      errors.push(`${label}: ${node.operator} needs at least two conditions.`)
      return null
    }
    const children: Condition[] = []
    node.children.forEach((child, index) => {
      const built = serializeCondition(child, leaves, `${label}, condition ${index + 1}`, errors, ownerBinding)
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
    const valueField = ownerBinding ? qualifyValueField(node.field, node.valueField, ownerBinding) : node.valueField.trim()
    return { field: node.field, operator: node.operator, valueField }
  }
  const literal = literalValue(leaf.type, node.valueText, node.valueBool, node.valueNull, label, node.field, errors)
  if (literal === NOT_READY) return null
  return { field: node.field, operator: node.operator, value: literal }
}

/** A local valueField names the then fact. When the left field belongs to another fact, say so explicitly. */
function qualifyValueField(left: string, valueField: string, thenBinding: string): string {
  const trimmed = valueField.trim()
  if (!trimmed || trimmed.startsWith("$") || !thenBinding) return trimmed
  const leftTrim = left.trim()
  const dot = leftTrim.indexOf(".")
  const leftOwner = leftTrim.startsWith("$") && dot > 0 ? leftTrim.slice(0, dot) : thenBinding
  if (leftOwner === thenBinding) return trimmed
  return `${thenBinding}.${trimmed}`
}

function serializeThen(
  block: ThenDraft,
  index: number,
  whens: WhenDraft[],
  leavesByWhen: Leaf[][],
  errors: string[],
  label = `Then block ${index + 1}`,
): ThenBlock | null {
  const binding = block.binding.trim()
  const whenIndex = whens.findIndex((when) => when.binding.trim() === binding)
  if (whenIndex < 0) {
    errors.push(`${label}: choose a when binding.`)
    return null
  }
  const branchDrafts = block.branches ?? []
  const dropBlank = branchDrafts.length > 0 && block.assignments.length > 0 && block.assignments.every(isUntouchedAssignment)
  const assignmentDrafts = dropBlank ? [] : block.assignments
  if (assignmentDrafts.length === 0 && branchDrafts.length === 0) {
    errors.push(`${label}: add an assignment.`)
    return null
  }
  const leaves = leavesByWhen[whenIndex] ?? []
  const assignments: Assignment[] = []
  const seen = new Set<string>()
  let complete = true
  assignmentDrafts.forEach((assignment, assignmentIndex) => {
    const built = serializeAssignment(assignment, leaves, `${label}, assignment ${assignmentIndex + 1}`, errors)
    if (!built) {
      complete = false
      return
    }
    if (seen.has(built.field)) errors.push(`${label}: ${built.field} is assigned more than once.`)
    seen.add(built.field)
    assignments.push(built)
  })
  if (!complete || assignments.length !== assignmentDrafts.length) return null

  const conditionLeaves = chainLeaves(binding, whens, leavesByWhen)
  const branches: Branch[] = []
  let branchesComplete = true
  branchDrafts.forEach((branch, branchIndex) => {
    const built = serializeChain(branch, label, branchIndex, binding, conditionLeaves, leaves, errors)
    if (!built) {
      branchesComplete = false
      return
    }
    branches.push(built)
  })
  if (!branchesComplete) return null
  const built: ThenBlock = { binding, assignments }
  if (branches.length > 0) built.branches = branches
  return built
}

function isUntouchedAssignment(assignment: AssignmentDraft): boolean {
  return (
    assignment.field.trim() === "" &&
    assignment.expression.trim() === "" &&
    assignment.valueText.trim() === "" &&
    !assignment.valueBool &&
    !assignment.valueNull
  )
}

function serializeChain(
  branch: BranchDraft,
  thenLabel: string,
  index: number,
  binding: string,
  conditionLeaves: Leaf[],
  assignmentLeaves: Leaf[],
  errors: string[],
): Branch | null {
  const label = `${thenLabel}, branch ${index + 1}`
  const ifArm = serializeArm(branch.ifArm, `${label}, if`, binding, conditionLeaves, assignmentLeaves, true, errors)
  const elseIf: BranchArm[] = []
  let complete = ifArm !== null
  branch.elseIf.forEach((arm, armIndex) => {
    const built = serializeArm(arm, `${label}, else if ${armIndex + 1}`, binding, conditionLeaves, assignmentLeaves, true, errors)
    if (!built) {
      complete = false
      return
    }
    elseIf.push(built)
  })
  let otherwise: BranchArm | null = null
  if (branch.otherwise) {
    otherwise = serializeArm(branch.otherwise, `${label}, else`, binding, conditionLeaves, assignmentLeaves, false, errors)
    if (!otherwise) complete = false
  }
  if (!complete || !ifArm) return null
  const built: Branch = { if: ifArm }
  if (elseIf.length > 0) built.elseIf = elseIf
  if (otherwise) built.else = otherwise
  return built
}

function serializeArm(
  arm: ArmDraft,
  label: string,
  binding: string,
  conditionLeaves: Leaf[],
  assignmentLeaves: Leaf[],
  requireWhen: boolean,
  errors: string[],
): BranchArm | null {
  let when: Condition | undefined
  if (requireWhen) {
    if (!arm.when) {
      errors.push(`${label}: add a condition.`)
      return null
    }
    const built = serializeCondition(arm.when, conditionLeaves, label, errors, binding)
    if (!built) return null
    when = built
  }
  if (arm.assignments.length === 0) {
    errors.push(`${label}: add an assignment.`)
    return null
  }
  const assignments: Assignment[] = []
  const seen = new Set<string>()
  let complete = true
  arm.assignments.forEach((assignment, assignmentIndex) => {
    const built = serializeAssignment(assignment, assignmentLeaves, `${label}, assignment ${assignmentIndex + 1}`, errors)
    if (!built) {
      complete = false
      return
    }
    if (seen.has(built.field)) errors.push(`${label}: ${built.field} is assigned more than once.`)
    seen.add(built.field)
    assignments.push(built)
  })
  if (!complete || assignments.length !== arm.assignments.length) return null
  const result: BranchArm = { assignments }
  if (when) result.when = when
  return result
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
