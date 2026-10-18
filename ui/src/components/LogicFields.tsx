import { ThenBlockEditor, WhenBlockEditor } from "./WhenThenEditors"
import { addFact, emptyThen, removeWhen, replaceWhen, valueFieldOptions, type RuleDraft } from "../drafts"
import type { Leaf, TypeResponse } from "../model"

export function LogicFields({
  draft,
  types,
  leavesByWhen,
  onChange,
}: {
  draft: RuleDraft
  types: TypeResponse[]
  leavesByWhen: Leaf[][]
  onChange: (recipe: (draft: RuleDraft) => RuleDraft) => void
}) {
  const bindings = [...new Set(draft.whens.map((when) => when.binding.trim()).filter(Boolean))]

  return (
    <>
      {draft.whens.map((when, index) => (
        <WhenBlockEditor
          key={when.id}
          index={index}
          when={when}
          types={types}
          leaves={leavesByWhen[index] ?? []}
          valueFields={valueFieldOptions(
            leavesByWhen[index] ?? [],
            draft.whens.slice(0, index).map((block, blockIndex) => ({
              binding: block.binding,
              leaves: leavesByWhen[blockIndex] ?? [],
            })),
          )}
          canRemove={draft.whens.length > 1}
          onChange={(next) => onChange((current) => replaceWhen(current, when.id, next))}
          onRemove={() => onChange((current) => removeWhen(current, when.id))}
        />
      ))}
      <button type="button" className="ghost" onClick={() => onChange(addFact)}>
        Add when block
      </button>
      {draft.thens.map((block, index) => {
        const whenIndex = draft.whens.findIndex((when) => when.binding.trim() === block.binding.trim())
        return (
          <ThenBlockEditor
            key={block.id}
            index={index}
            block={block}
            bindings={bindings}
            leaves={whenIndex >= 0 ? (leavesByWhen[whenIndex] ?? []) : []}
            whens={draft.whens}
            leavesByWhen={leavesByWhen}
            canRemove={draft.thens.length > 1}
            onChange={(next) =>
              onChange((current) => ({ ...current, thens: current.thens.map((item) => (item.id === block.id ? next : item)) }))
            }
            onRemove={() => onChange((current) => ({ ...current, thens: current.thens.filter((item) => item.id !== block.id) }))}
          />
        )
      })}
      <button
        type="button"
        className="ghost"
        onClick={() => onChange((current) => ({ ...current, thens: [...current.thens, emptyThen(current.whens[0]?.binding ?? "")] }))}
      >
        Add then block
      </button>
    </>
  )
}
