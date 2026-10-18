export const SCALAR_TYPES = [
  "String",
  "int",
  "long",
  "double",
  "boolean",
  "Integer",
  "Long",
  "Double",
  "Boolean",
] as const

export type ScalarName = (typeof SCALAR_TYPES)[number]

export type FieldDecl = {
  name: string
  type?: string | null
  description?: string | null
  fields?: FieldDecl[] | null
}

export type TypeResponse = {
  name: string
  description?: string | null
  fields: FieldDecl[] | null
  createdAt?: string
}

export type FactSchema = {
  type: string
  fields: FieldDecl[] | null
}

export type Condition = {
  field?: string
  operator: string
  value?: unknown
  valueField?: string
  conditions?: Condition[]
}

export type WhenBlock = {
  binding: string
  type: string
  conditions?: Condition | null
}

export type Assignment = {
  field: string
  value?: unknown
  expression?: string
}

/** One arm of an if / else-if / else chain. Else has no `when`. */
export type BranchArm = {
  when?: Condition | null
  assignments: Assignment[]
}

/** An exclusive chain. The first matching arm runs. */
export type Branch = {
  if: BranchArm
  elseIf?: BranchArm[] | null
  else?: BranchArm | null
}

export type ThenBlock = {
  binding: string
  assignments: Assignment[]
  /** If / else-if / else chains. They run after `assignments`, once the when condition matches. */
  branches?: Branch[] | null
}

export type RuleDefinition = {
  salience?: number | null
  inputSchema?: FactSchema[] | null
  outputSchema?: FactSchema[] | null
  when?: WhenBlock[] | null
  then?: ThenBlock[] | null
}

export type EffectiveSegment = {
  effectiveFrom: string
  effectiveUntil: string | null
}

export type StageStatus = "complete" | "current" | "upcoming"

export type StageMark = {
  name: string
  label: string
  status: StageStatus
}

/** Present only while a rejection has sent the version back to draft. */
export type RejectionNote = {
  stage: string
  label: string
  comment: string
  at?: string | null
}

export type ApprovalWorkflow = {
  id: number
  versionNumber: number
  version: string
  active: boolean
  /** Labels from Draft through Approved. */
  stages: string[]
  /** Names between Draft and Approved. */
  middleStages: string[]
  createdAt?: string
}

export type RuleVersion = {
  id: number | null
  versionNumber: number
  version: string
  description: string | null
  effectiveFrom: string
  effectiveUntil: string | null
  /** Periods in which this version runs. Absent on a response that only has the outer pair. */
  segments?: EffectiveSegment[] | null
  active: boolean
  definition: RuleDefinition | null
  drl: string | null
  createdAt?: string
  /** Missing on a response from a server that predates approval. Null counts as already approved. */
  stage?: string | null
  stageLabel?: string | null
  editable?: boolean | null
  workflowVersion?: string | null
  /** The path this version follows, marked for the latest pass from draft. */
  stages?: StageMark[] | null
  returnedFrom?: RejectionNote | null
}

export type RuleResponse = {
  id: number
  code: string | null
  name: string | null
  description: string | null
  definition: RuleDefinition | null
  drl: string | null
  createdAt?: string
  activeVersion?: string | null
  versions?: RuleVersion[]
}

export type FactResult = {
  type: string
  values: Record<string, unknown> | null
}

export type ExecutionResponse = {
  ruleId: number
  code: string | null
  version: string | null
  fired: boolean
  reasoning: string
  inputData: FactResult[] | null
  outputData: FactResult[] | null
  modifiedData: FactResult[] | null
}

export type Leaf = {
  path: string
  type: string
  /** Shown in a menu. The stored path stays in `path`. */
  label?: string
}

export function leafText(leaf: Leaf): string {
  return `${leaf.label ?? leaf.path} · ${leaf.type}`
}

const SCALAR_SET: ReadonlySet<string> = new Set(SCALAR_TYPES)

export function isScalar(type: string | null | undefined): type is ScalarName {
  return !!type && SCALAR_SET.has(type)
}

export function isBooleanType(type: string): boolean {
  return type === "boolean" || type === "Boolean"
}

export function isIntegerType(type: string): boolean {
  return type === "int" || type === "long" || type === "Integer" || type === "Long"
}

export function isNumericType(type: string): boolean {
  return isIntegerType(type) || type === "double" || type === "Double"
}

export function isPrimitive(type: string): boolean {
  return type === "int" || type === "long" || type === "double" || type === "boolean"
}

export type ControlKind = "boolean" | "integer" | "decimal" | "string"

export function controlKind(type: string | null | undefined): ControlKind {
  if (!type || !isScalar(type)) return "string"
  if (isBooleanType(type)) return "boolean"
  if (isIntegerType(type)) return "integer"
  if (isNumericType(type)) return "decimal"
  return "string"
}

export function operatorsFor(type: string): string[] {
  if (type === "String") return ["==", "!=", "contains", "matches"]
  if (isBooleanType(type)) return ["==", "!="]
  if (isNumericType(type)) return ["==", "!=", ">", ">=", "<", "<="]
  return ["==", "!="]
}

export function sameFamily(left: string, right: string): boolean {
  if (left === "String" || right === "String") return left === right
  if (isBooleanType(left) || isBooleanType(right)) return isBooleanType(left) && isBooleanType(right)
  if (isNumericType(left) && isNumericType(right)) return true
  return left === right
}

export function catalogOf(types: TypeResponse[]): Map<string, TypeResponse> {
  return new Map(types.map((type) => [type.name, type]))
}

export function expandLeaves(
  fields: FieldDecl[] | null | undefined,
  catalog: Map<string, TypeResponse>,
  prefix = "",
  seen: ReadonlySet<string> = new Set(),
): Leaf[] {
  const leaves: Leaf[] = []
  for (const field of fields ?? []) {
    if (!field?.name) continue
    const path = prefix ? `${prefix}.${field.name}` : field.name
    if (field.fields && field.fields.length > 0) {
      leaves.push(...expandLeaves(field.fields, catalog, path, seen))
      continue
    }
    if (isScalar(field.type)) {
      leaves.push({ path, type: field.type })
      continue
    }
    if (field.type && catalog.has(field.type) && !seen.has(field.type)) {
      const next = new Set(seen)
      next.add(field.type)
      leaves.push(...expandLeaves(catalog.get(field.type)?.fields, catalog, path, next))
    }
  }
  return leaves
}

export function bindingFor(typeName: string): string {
  if (!typeName) return "$fact"
  return `$${typeName.charAt(0).toLowerCase()}${typeName.slice(1)}`
}

export function uid(): string {
  return crypto.randomUUID()
}
