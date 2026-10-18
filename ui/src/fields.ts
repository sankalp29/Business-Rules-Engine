import type { FieldDecl } from "./model"

/** Keeps fields whose name, type, or description matches. A matching parent keeps its children. */
export function matchingFields(fields: FieldDecl[] | null | undefined, query: string): FieldDecl[] {
  const list = fields ?? []
  const needle = query.trim().toLowerCase()
  if (!needle) return list
  const kept: FieldDecl[] = []
  for (const field of list) {
    const children = matchingFields(field.fields, needle)
    const blob = `${field.name} ${field.type ?? ""} ${field.description ?? ""}`.toLowerCase()
    if (blob.includes(needle)) kept.push(field)
    else if (children.length > 0) kept.push({ ...field, fields: children })
  }
  return kept
}
